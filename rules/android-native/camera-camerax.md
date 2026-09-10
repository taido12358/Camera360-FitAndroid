# CameraX Rules

- `ImageCapture` runs on a single-thread `Executor`
  (`Executors.newSingleThreadExecutor()` in `CaptureViewModel`) — reuse
  this pattern for any new capture-related work rather than spinning up
  additional executors; shut them down in `onCleared()` as already done.
- Runtime `CAMERA` permission is required (declared in
  `AndroidManifest.xml`) — handle denial explicitly in the UI; do not
  assume the permission is granted (see rules/security/permissions-privacy.md).
- The assumed horizontal FOV (`StitchingEngine.CAMERA_HFOV_DEG = 65.0`) is
  not derived from CameraX/`CameraCharacteristics` — if you change camera
  selection, lens, or zoom, verify this constant still matches or the
  stitching output will be wrong. See docs/modules/camera360-stitching.md.
