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

## Manual mode for phones without a rotation-vector sensor (2026-09-20)

`CaptureState.isManualMode` (= `hasGyroscope == false`), found on the real test
phone (Galaxy A12: accelerometer only). Instead of refusing to work:

- `GravityManager` streams world-up in device coordinates from the
  accelerometer (`TYPE_GRAVITY` if present, else low-passed
  `TYPE_ACCELEROMETER`). Each shot stores that vector (`ManualShot`) — this
  fixes pitch and roll exactly.
- No AR dots / 24-frame targets / auto-capture. `ManualModePanel` shows shot
  count + how to shoot (rotate slowly, ~25-30 deg per shot, >= 40 % overlap,
  hold still). Bottom row: undo-last / shutter / stitch (needs >= 2 shots).
- Stitching (`CaptureViewModel.stitchManual`): downscale each photo to a
  192 px grayscale copy, `YawRegistration.estimateHeadings` recovers each
  photo's heading from image content, `PoseMath.rotationFromGravity` builds the
  full pose, then the normal `StitchingEngine` renders (with gain
  compensation). Photos that cannot be linked to the rest are dropped with a
  notice; if fewer than 2 link, a Vietnamese error explains how to reshoot.

## Live shooting feedback in manual mode (2026-09-20)

Without a rotation-vector sensor there is no AR guidance, so the accelerometer
drives a small HUD line in `ManualModePanel` (pure logic in `GravityMath`,
JVM-tested): camera elevation ("Ngang"), roll about the camera axis
("Nghieng"; warns above 8 deg), and a steadiness flag (gravity direction moved
<= 0.8 deg over the last 400 ms) that turns the shutter ring yellow. Blur and roll
are what hurt registration most. The 50 Hz gravity stream is kept out of
`CaptureState`; only rounded tilt/roll/steady changes are published, and the
shutter reads the newest vector directly (`latestUp`). Verified on the Galaxy
A12 lying flat: "Ngang -86, Nghieng 0, da giu yen".

## In-app panorama viewer (2026-09-20)

The completion card now has "Xem ảnh" (opens `PanoramaViewer`: full screen,
pinch-zoom up to 8x, drag to pan, double-tap to reset, width capped at 4096 px
when decoding), "Mở Thư viện" and "Chụp lại". The old gallery intent carried no
image URI, so it could not be relied on to show the panorama. Verified on the
emulator.

## Coverage advice after stitching (2026-09-20)

`StitchingEngine.stitch` returns `CoverageStats.Azimuth` (pure, JVM-tested,
`CoverageStatsTest`): the share of the 360 deg circle seen by any photo and the
largest circular gap. In manual mode a gap of 15 deg or more is reported in the
completion notice ("Ảnh phủ N% vòng ngang, còn hở khoảng G°...") so the user
knows where to shoot more. Guided mode ignores the result (it always aims at
full coverage).
