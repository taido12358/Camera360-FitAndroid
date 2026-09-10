# Module: Camera360 Capture

Files: `CaptureViewModel.kt`, `GyroscopeManager.kt`,
`ui/CaptureScreen.kt`. For the full flow diagram see
docs/architecture/data-flow.md — this file covers contracts and gotchas
only, not the flow itself.

## Public surface

- `CaptureViewModel.state: StateFlow<CaptureState>` — single source of
  truth for the capture screen. `CaptureScreen` should only read this and
  call `startSensor`, `capturePhoto`, `stitchPanorama`, `resetForNewSession`.
- `GyroscopeManager.orientationFlow(): Flow<DeviceOrientation>` — cold flow,
  registers/unregisters the sensor listener on collect/close. Do not hold a
  `GyroscopeManager` instance across `ViewModel` recreation without
  re-collecting.

## Gotchas

- `GyroscopeManager.hasGyroscope` can be `false` on devices without a
  rotation-vector sensor — `orientationFlow()` closes immediately in that
  case. Any caller must handle "no sensor" (currently the ViewModel does
  not surface this to the UI — worth fixing before shipping to devices
  without a gyroscope).
- Frame targets are generated once per `CaptureViewModel` instance
  (`generateFrames()` called in the `CaptureState()` default). A
  process/activity recreation resets capture progress — there is no saved
  instance state / persistence across process death.
- Azimuth smoothing uses a wrap-aware lerp (`GyroscopeManager`, `alpha =
  0.25f`) — do not replace with naive linear interpolation, it will jump at
  the 0°/360° boundary.
