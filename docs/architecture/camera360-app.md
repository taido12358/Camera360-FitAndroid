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
2. `GyroscopeManager` streams smoothed device orientation (azimuth/pitch,
   for UI/guidance) **and** the raw device→world rotation matrix (full 3D
   orientation, for accurate pose capture); the ViewModel tracks
   `nearestUncapturedIndex` and `isAligned` to guide the user.
3. `CaptureScreen`'s `SphereGuideOverlay` renders the 24 targets as dots
   "fixed in space" over the live camera preview — each dot's screen
   position is recomputed every frame via real perspective projection
   (`projectToScreen`) from the target's world azimuth/pitch and the
   device's current orientation, so dots appear to stay anchored to real
   world directions as the phone rotates (an AR effect, no ARCore
   dependency). Captured targets turn green with a checkmark; the nearest
   uncaptured target is highlighted; the aligned target turns yellow.
4. **Auto-capture**: `CaptureViewModel.startAutoCapture()` polls alignment
   and fires `capturePhoto()` automatically once the device holds
   alignment to the nearest target for a short stable-hold window (avoids
   firing on a quick pass-through / motion blur) — "chỉ chụp khi xoay
   đúng vị trí". `CaptureState.holdProgress` (0..1) drives a UI
   countdown-style indicator during the hold. A manual shutter button
   remains as a fallback (e.g. if the device has no gyroscope, alignment
   can never be detected).
5. On capture, CameraX `ImageCapture` saves a JPEG to `filesDir/frames/`,
   tagged with the device's actual azimuth/pitch **and** full rotation
   matrix at that exact moment (not the nominal target) — the matrix is
   what stitching uses for accurate pose.
6. Each captured frame is also mirrored to the public gallery
   (`MediaStore` on API 29+, direct file copy + `MediaScannerConnection`
   below that) via `copyToGallery`.

## Stitching flow

- `StitchingEngine.stitch()` runs entirely on `Dispatchers.IO`, no ML/CV
  library — inverse equirectangular projection using each frame's **full
  captured rotation matrix** (not just azimuth/pitch) instead of feature
  matching, so accuracy holds even if the phone was rolled/tilted between
  shots.
- Frames are downsampled to a max long side of 1024px before stitching to
  bound peak memory; output is a fixed 3840×1920 equirectangular JPEG.
- Blending uses cosine-weighted averaging across overlapping frames.
- Progress is reported via a callback consumed by the ViewModel's
  `StateFlow` (`stitchProgress`), safe to observe from Compose.

## Constraints worth knowing before changing this code

- Horizontal FOV: `CaptureScreen` measures the bound camera's real FOV
  from `CameraCharacteristics` (focal length + sensor physical size) via
  `Camera2CameraInfo` and stores it in `CaptureState.measuredHFovDeg`,
  used by both the AR overlay and the final stitch. `StitchingEngine.
  CAMERA_HFOV_DEG = 65.0` is now only a **fallback** for when measurement
  fails — do not assume the constant is what's actually used at runtime.
- Output resolution (3840×1920) and `MAX_FRAME_LONG_SIDE` (1024) are memory
  vs. quality tradeoffs — see rules/android-native/image-processing.md
  before changing them.
- Stitching pose accuracy depends on `capturedRotationMatrix` being
  non-null for every frame — it's null if the device had no working
  rotation-vector sensor at capture time; such frames are skipped
  (reported as "thiếu N frame") rather than stitched with guessed pose.

## Rules

See rules/android-native/README.md for Kotlin/Compose conventions,
CameraX usage, and sensor/image-processing constraints.
