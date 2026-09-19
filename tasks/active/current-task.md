# TASK-004

## Title

Camera360 on phones without a rotation-vector sensor (manual mode) + hardening
found by real testing.

## Goal

The real test phone (Galaxy A12, SM-A127F) has only an accelerometer, so the
sensor-driven pipeline (TASK-003) cannot run on it. Make Camera360 useful on such
phones: free-form manual capture, gravity for pitch/roll, image registration for
heading, then the existing renderer.

## Scope (done)

- `GravityManager`, `ManualShot`, manual-mode capture + UI (instructions, undo, stitch).
- `PoseMath.rotationFromGravity`, `YawRegistration` (NCC registration, voting
  placement, robust LS, consistency prune), `CoverageCrop`, `GainCompensation`.
- Fixes found by running on the emulator/phone: camera-forward azimuth/pitch,
  ENU axis swap, EXIF rotation, FOV semantics, portrait lock, overlay roll.
- Tests: 36 JVM unit tests + 1 on-device benchmark (`androidTest`).

## Testing

- JVM: `./gradlew testDebugUnitTest` (36 tests: PoseMath 9, GainCompensation 6,
  YawRegistration 15 incl. real-photo texture / exposure drift / noise /
  30-photo three-row sweep, CoverageCrop 6).
- Device: `./gradlew connectedDebugAndroidTest` on the Galaxy A12: 30 photos
  registered in ~9 s, all linked, error < 3 deg (synthetic sphere).
- Emulator: guided 24-frame capture + stitch end to end (with sensors driven via
  accelerometer + magnetometer).
- Manual mode UI smoke-tested on the real phone (counter, undo, stitch, clear
  Vietnamese error on featureless dark photos).

## NOT verified (needs the user)

Manual mode with real overlapping photos on the phone: hold the phone, rotate
slowly in a textured room taking a shot every ~25-30 deg (>= 40 % overlap), tap
"Ghep anh", then inspect the panorama and the original shots (DCIM/Camera360).
Also unverified on real data: gain compensation quality, accuracy of the FOV
measured from CameraCharacteristics on the A12.

## Status

IMPLEMENTED + unit/device-benchmark TESTED; real-photo behaviour on the phone
NOT VERIFIED.
