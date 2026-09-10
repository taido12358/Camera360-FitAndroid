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
