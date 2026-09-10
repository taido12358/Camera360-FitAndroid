# Camera360 App Architecture (native Android)

Source of truth for how the Camera360 native app is structured. This is a
**standalone Android app**, not the React Native host for GoldenCare —
see docs/architecture/README.md.

## Entry point

- `android/app/src/main/AndroidManifest.xml` declares `.MainActivity` as
  the launcher activity (`MAIN`/`LAUNCHER` intent filter). There is no RN
  bridge or `MainApplication`.

## Structure

```
android/app/src/main/java/com/camera360/
├── MainActivity.kt         Compose host, entry point
├── CaptureViewModel.kt     ViewModel + StateFlow — capture session state,
│                           drives capture + stitch flow
├── GyroscopeManager.kt     Wraps SensorManager (TYPE_ROTATION_VECTOR),
│                           exposes smoothed azimuth/pitch as a Flow
├── StitchingEngine.kt      Pure algorithm: equirectangular panorama
│                           stitcher, no Android UI dependency
└── ui/
    ├── CaptureScreen.kt    Capture UI (Compose)
    └── theme/Theme.kt      Material3 dark theme
```

## Capture flow

1. `CaptureViewModel.generateFrames()` defines 24 fixed world-space targets
   (3 pitch rows × 8 azimuth columns) covering the full sphere.
2. `GyroscopeManager` streams smoothed device orientation; the ViewModel
   tracks `nearestUncapturedIndex` and `isAligned` to guide the user.
3. On shutter press (`capturePhoto`), CameraX `ImageCapture` saves a JPEG to
   `filesDir/frames/`, tagged with the *actual* orientation at capture time
   (not the nominal target) — this is what makes stitching accurate.
4. Each captured frame is also mirrored to the public gallery
   (`MediaStore` on API 29+, direct file copy + `MediaScannerConnection`
   below that) via `copyToGallery`.

## Stitching flow

- `StitchingEngine.stitch()` runs entirely on `Dispatchers.IO`, no ML/CV
  library — inverse equirectangular projection using known camera poses
  (azimuth/pitch) instead of feature matching.
- Frames are downsampled to a max long side of 1024px before stitching to
  bound peak memory; output is a fixed 3840×1920 equirectangular JPEG.
- Blending uses cosine-weighted averaging across overlapping frames.
- Progress is reported via a callback consumed by the ViewModel's
  `StateFlow` (`stitchProgress`), safe to observe from Compose.

## Constraints worth knowing before changing this code

- `CAMERA_HFOV_DEG = 65.0` is an **assumed** fixed horizontal FOV — it is
  not read from `CameraCharacteristics`, so accuracy depends on this
  constant matching the actual device/lens.
- Output resolution (3840×1920) and `MAX_FRAME_LONG_SIDE` (1024) are memory
  vs. quality tradeoffs — see rules/android-native/image-processing.md
  before changing them.

## Rules

See rules/android-native/README.md for Kotlin/Compose conventions,
CameraX usage, and sensor/image-processing constraints.
