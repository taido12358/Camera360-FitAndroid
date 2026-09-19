# Sensor (Gyroscope) Rules

- `GyroscopeManager` uses `TYPE_ROTATION_VECTOR` at `SENSOR_DELAY_GAME` —
  do not change to a slower delay without checking capture-alignment UX;
  do not switch sensor type without checking `getRotationMatrixFromVector`
  compatibility.
- `hasGyroscope` can be `false` — any caller must handle the no-sensor case
  (currently not surfaced to the UI, see docs/modules/camera360-capture.md
  gotchas). Don't assume every device has this sensor.
- Azimuth smoothing must stay wrap-aware (handles the 360°→0° boundary) —
  see the `diff` calculation in `GyroscopeManager.orientationFlow()`. A
  naive `lerp` will visibly jump near due-north.
- Always `unregisterListener` on flow close (`awaitClose` already does
  this) — never leave a sensor listener registered past the screen's
  lifecycle; it drains battery.
- `DeviceOrientation.rotationMatrix` is the **raw, unsmoothed** device→world
  matrix, copied fresh per sensor event (`rotMatrix.copyOf()`) — never
  emit the listener's reused `rotMatrix` array by reference, and never
  apply the azimuth/pitch smoothing to it. It exists specifically for
  accurate stitching pose (`StitchingEngine`); azimuth/pitch stay smoothed
  for on-screen guidance only. Keep these two uses separate.

## Azimuth/pitch convention (added 2026-09-19, verified on emulator)

- `DeviceOrientation.azimuth`/`pitch` are the **back camera's forward
  direction**: azimuth = compass heading (0° = north, clockwise), pitch =
  elevation (0° = horizon, +90° = straight up). They are derived from
  `-column 2` of the rotation matrix, in the ENU world frame.
- Do **not** use `SensorManager.getOrientation()` for these: its pitch is the
  tilt of the phone's Y axis (≈ -90° when the phone is held upright with the
  camera at the horizon) and its azimuth is degenerate in that exact pose.
  Frame targets (-35°/0°/+35°) and `projectToScreen` assume camera elevation.
- The rotation matrix's world frame is ENU (x=E, y=N, z=Up). `StitchingEngine`
  builds world directions as (E, Up, N), so `cameraBasisFromRotationMatrix`
  swaps components 1 and 2 of each basis vector. Keep both sides in sync.
- Emulator testing: `adb emu sensor set orientation` does NOT move the
  rotation vector. Drive `acceleration` + `magnetic-field` instead (the fused
  rotation vector is computed from them).
