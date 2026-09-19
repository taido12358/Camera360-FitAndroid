# Module: Camera360 Stitching Engine

File: `StitchingEngine.kt`. Pure Kotlin object, no Android UI dependency —
safe to unit test in isolation (see rules/testing/unit.md).

## Public surface

- `StitchingEngine.stitch(inputs: List<FrameInput>, outputFile: File,
  hFovDeg: Double = CAMERA_HFOV_DEG, onProgress: (Float) -> Unit)` —
  suspend function, runs on `Dispatchers.IO`. Requires exactly the frames
  the caller collected; it does not validate frame count or coverage —
  that check lives in `CaptureViewModel.stitchPanorama`
  (`inputs.size < s.totalFrames`). Callers should pass the camera's
  measured FOV (`CaptureState.measuredHFovDeg`) rather than relying on the
  default fallback constant.
- `FrameInput(file: File, rotationMatrix: FloatArray)` — `rotationMatrix`
  is the 9-float device→world rotation matrix captured at shutter press
  (see `CaptureViewModel.FrameTarget.capturedRotationMatrix`), **not**
  azimuth/pitch. This is what makes stitching accurate even if the phone
  was rolled/tilted between shots — see
  docs/architecture/camera360-app.md.

## Known constants/inputs that affect output quality/cost

- `hFovDeg` (parameter, defaults to `CAMERA_HFOV_DEG = 65.0`) — prefer the
  value CaptureScreen measures from `CameraCharacteristics` at runtime;
  the constant is only a fallback. Wrong FOV produces visible
  seams/ghosting.
- `OUT_W/OUT_H = 3840×1920` — fixed output size regardless of input
  resolution.
- `MAX_FRAME_LONG_SIDE = 1024` — frames are downsampled before stitching;
  raising this improves detail but increases peak memory roughly
  quadratically.

## Gotchas

- `stitch()` throws `IllegalStateException` if every frame fails to
  decode — callers must catch this (`CaptureViewModel` does, via
  `stitchError`).
- Camera basis (`right`/`up`/`fwd`) is derived from `rotationMatrix` via
  `cameraBasisFromRotationMatrix`, which assumes the back camera lens
  points along the device's `-Z` axis (standard orientation-sensor
  convention: device Z points out of the screen face). If a future change
  targets the front camera or a device with a different sensor mounting,
  this assumption must be revisited.
- The algorithm still has no feature matching/alignment correction beyond
  using the true captured pose — stitching quality now depends on (a) the
  rotation matrix being accurate (it is, since it comes straight from the
  system's sensor fusion) and (b) `hFovDeg` matching the real lens. Do not
  "fix" seams by tweaking the blend weights before checking those two
  first.

## Renderer: parallel + bilinear (2026-09-19)

`StitchingEngine.stitch` renders output rows in parallel (interleaved row
sets across up to 8 `Dispatchers.Default` workers, see `renderRow`), samples
source frames bilinearly instead of nearest-pixel, and reuses per-column
sin/cos tables. On the emulator this took the 24-frame stitch from ~50 s to
~26 s. Output pixels are identical in geometry; only sampling smoothness
changed. `onProgress` is now called from worker threads (still safe for a
StateFlow), so values may arrive very slightly out of order.
