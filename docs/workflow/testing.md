# Testing Workflow

See rules/testing/README.md for what must be tested and how. Summary of
what exists today:

## Camera360 (native Android)

- JVM unit tests exist for the pose math (`android/app/src/test`, 9 tests in
  `PoseMathTest`; run `./gradlew testDebugUnitTest` in `android/`). No
  `androidTest`, no StitchingEngine/CaptureViewModel tests yet (see
  rules/testing/unit.md).
- Manual QA is currently the only verification method — see
  rules/testing/manual-qa.md before merging capture/stitching changes.

## GoldenCare (React Native)

- `package.json` has a `jest` preset (`react-native`) wired via
  `npm test`, but no test files exist yet.

## Before marking a task TESTED or VERIFIED

Follow the status definitions in rules/ai-agent/after-coding.md — do not
use "done" language until the relevant tests above actually exist and
pass.

## Testing manual mode on a phone/emulator that has a rotation-vector sensor (2026-09-20)

Debug builds accept a launch flag that forces the accelerometer-only mode, so the whole
in-app flow (shots, tilt/steadiness HUD, stitch, coverage notice, diagnostics file,
viewer, gallery) can be exercised without a sensor-less phone:

    adb shell am start -n com.camera360/.MainActivity --ez force_manual true

Only honoured when the app is debuggable (`FLAG_DEBUGGABLE`). On the emulator every pose
yields the same picture, so this checks the *integration* (registration puts identical
photos at heading 0, coverage notice, files) and not seam quality. Verified 2026-09-20:
8 shots -> 8/8 placed, stitch 7.7 s, diagnostics written, notice "Ảnh phủ 24% vòng
ngang, còn hở khoảng 272°". Leftover working photos from an earlier process are deleted
at the next start (`cleanLeftoverFiles`).
