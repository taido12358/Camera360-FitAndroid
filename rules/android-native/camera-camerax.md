# CameraX Rules

- `ImageCapture` runs on a single-thread `Executor`
  (`Executors.newSingleThreadExecutor()` in `CaptureViewModel`) — reuse
  this pattern for any new capture-related work rather than spinning up
  additional executors; shut them down in `onCleared()` as already done.
- Runtime `CAMERA` permission is required (declared in
  `AndroidManifest.xml`) — handle denial explicitly in the UI; do not
  assume the permission is granted (see rules/security/permissions-privacy.md).
- Horizontal FOV is measured at runtime from `CameraCharacteristics` via
  `Camera2CameraInfo` (`CaptureScreen.measureHorizontalFovDeg`), stored in
  `CaptureState.measuredHFovDeg`. `StitchingEngine.CAMERA_HFOV_DEG = 65.0`
  is only the fallback if measurement fails — don't reintroduce a
  hardcoded FOV assumption elsewhere. If you change camera selection,
  lens, or zoom, verify the measurement path still returns a sane value.
  See docs/modules/camera360-stitching.md.
- `Camera2CameraInfo`/`getCameraCharacteristic` are gated behind
  `@ExperimentalCamera2Interop` — any new use needs
  `@OptIn(ExperimentalCamera2Interop::class)` on the containing function.
