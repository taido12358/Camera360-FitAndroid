# Module: Camera360 Capture

Files: `CaptureViewModel.kt`, `GyroscopeManager.kt`,
`ui/CaptureScreen.kt` (including `SphereGuideOverlay`, `projectToScreen`,
`measureHorizontalFovDeg`). For the full flow diagram see
docs/architecture/data-flow.md — this file covers contracts and gotchas
only, not the flow itself.

## Public surface

- `CaptureViewModel.state: StateFlow<CaptureState>` — single source of
  truth for the capture screen. `CaptureScreen` should only read this and
  call `startSensor`, `startAutoCapture`, `capturePhoto`,
  `stitchPanorama`, `resetForNewSession`, `setMeasuredHFov`.
- `GyroscopeManager.orientationFlow(): Flow<DeviceOrientation>` — cold flow,
  registers/unregisters the sensor listener on collect/close. Emits both
  smoothed azimuth/pitch (UI guidance) and the raw device→world rotation
  matrix (stitching pose) per event. Do not hold a `GyroscopeManager`
  instance across `ViewModel` recreation without re-collecting.
- `CaptureViewModel.startAutoCapture(imageCapture, context)` — idempotent
  (guarded by an internal job reference); starts a polling loop that
  fires `capturePhoto` once alignment holds steady for
  `AUTO_CAPTURE_STABLE_MS`. Call once per screen lifetime (e.g. from a
  `LaunchedEffect(Unit)`), not per recomposition.
- `SphereGuideOverlay` (Compose, `CaptureScreen.kt`) — renders the 24
  targets as AR-style dots using `projectToScreen`, which does real
  perspective projection from world (azimuth, pitch) to screen space
  given the device's *smoothed* azimuth/pitch (roll is not modeled here —
  see gotchas). Uses `CaptureState.measuredHFovDeg` when available.

## Gotchas

- `GyroscopeManager.hasGyroscope` can be `false` on devices without a
  rotation-vector sensor — `orientationFlow()` closes immediately in that
  case, so `currentAzimuth`/`currentPitch` stay at their default `(0,0)`
  and `currentRotationMatrix` stays `null` forever. This is not surfaced
  to the UI, and it also means: (a) `isAligned` can spuriously read true
  for whichever target sits near azimuth/pitch (0,0), and (b) every
  frame's `capturedRotationMatrix` will be null, so `stitchPanorama` will
  always report frames missing. Worth fixing before shipping to devices
  without a gyroscope.
- Frame targets are generated once per `CaptureViewModel` instance
  (`generateFrames()` called in the `CaptureState()` default). A
  process/activity recreation resets capture progress — there is no saved
  instance state / persistence across process death.
- Azimuth smoothing uses a wrap-aware lerp (`GyroscopeManager`, `alpha =
  0.25f`) — do not replace with naive linear interpolation, it will jump at
  the 0°/360° boundary. This smoothing applies to azimuth/pitch only; the
  rotation matrix emitted alongside it is the *raw*, unsmoothed sensor
  fusion output (deliberately — stitching pose should not be smoothed).
- `SphereGuideOverlay`'s live projection (`projectToScreen`) reconstructs
  the camera's basis from azimuth/pitch and **assumes zero roll** — if the
  user tilts the phone sideways, dot positions on screen will be slightly
  off from where `StitchingEngine` (which uses the true rotation matrix,
  roll included) will actually sample. This is a known, accepted gap: the
  live overlay is a guidance aid, the captured pose used for stitching is
  the accurate one.
- Auto-capture (`startAutoCapture`) polls every `AUTO_CAPTURE_POLL_MS`
  (60ms) and requires `AUTO_CAPTURE_STABLE_MS` (350ms) of continuous
  alignment before firing — tune both together if capture feels too eager
  or too slow; don't change one without checking the other's effect on
  false-trigger rate.

## AR overlay uses the full rotation matrix (2026-09-19)

`SphereGuideOverlay` projects dots with `projectToScreenWithMatrix` (full
device→world rotation matrix, same right/up/-forward convention as
`StitchingEngine`), so phone roll is respected — dots and the per-row level
lines rotate with the phone. The az/pitch-only `projectToScreen` is now only
a fallback when no matrix has arrived yet. Verified on emulator with a 30°
roll pose (`scratchpad` pose script: accel+mag).
