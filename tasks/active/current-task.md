# TASK-003

## Title

Auto-capture on alignment + rotation-matrix-accurate stitching + measured FOV

## Goal

User asked for three things: (1) world-fixed dots showing capture
positions "in space" — already existed (`SphereGuideOverlay`, not
documented in the earlier session), (2) auto-capture the shot only once
the phone rotates into the correct position instead of requiring a manual
tap, (3) make the panorama stitching algorithm "thật chuẩn" (very
accurate).

## Scope

- `GyroscopeManager.kt`: expose the raw device→world rotation matrix
  alongside smoothed azimuth/pitch.
- `CaptureViewModel.kt`: store `capturedRotationMatrix` per frame; add
  `startAutoCapture` (polls alignment, fires `capturePhoto` after a
  350ms stable hold, exposes `holdProgress` for UI feedback); add
  `setMeasuredHFov`/`measuredHFovDeg`.
- `StitchingEngine.kt`: `FrameInput` now carries the full rotation matrix
  instead of azimuth/pitch; per-frame camera basis (right/up/fwd) is
  derived from that matrix instead of an azimuth/pitch reconstruction
  that assumed zero roll; `stitch()` takes an `hFovDeg` parameter instead
  of always using the hardcoded constant.
- `CaptureScreen.kt`: measure the bound camera's real horizontal FOV via
  `Camera2CameraInfo`/`CameraCharacteristics` and feed it to both the AR
  overlay and the stitch call; wire up `startAutoCapture`; show a
  hold-progress indicator during auto-capture; keep the manual shutter as
  a labeled fallback.
- Documentation: docs/architecture/camera360-app.md,
  docs/modules/camera360-capture.md, docs/modules/camera360-stitching.md,
  docs/project/requirements.md, rules/android-native/camera-camerax.md,
  rules/android-native/sensors-gyroscope.md.

## Dependencies

TASK-002 (confirmed Camera360 is the project).

## Affected App/Module

Camera360 (native Android) — capture + stitching.

## Acceptance Criteria

- Stitching pose comes from the full captured rotation matrix, not
  azimuth/pitch reconstruction (fixes roll-induced misalignment).
- FOV used for both live AR overlay and final stitch comes from measured
  `CameraCharacteristics` when available, constant only as fallback.
- Capture fires automatically once alignment holds steady; manual shutter
  still works as a fallback.
- No regressions to the existing AR dot overlay, gallery mirroring, or
  error handling paths.

## Testing

**Not done.** No network access in this environment to fetch Gradle/
dependencies, so the build could not be compiled or run here. Manually
reviewed all changed files for type/logic correctness (see
logs/ai-agent/sessions/2026/09/2026-09-10-session-003.md), but this has
**not** been verified on a real device. Follow
rules/testing/manual-qa.md (full 24-frame capture + stitch, including a
deliberately tilted/rolled capture to confirm the rotation-matrix fix
actually helps) before treating this as working.

## Documentation

Updated as listed in Scope above.

## Status

IMPLEMENTED (not TESTED, not VERIFIED — see Testing above)
