# Unit Testing Rules

## Camera360 (native Android)

- `StitchingEngine` is the highest-value unit-test target: it is pure
  Kotlin with no Android framework dependency beyond `android.graphics`
  (Bitmap/Color) — usable under Robolectric or with a fake bitmap layer.
  A JVM unit-test source set now exists (`android/app/src/test`, JUnit 4;
  run `./gradlew testDebugUnitTest` from `android/`). First tests:
  `PoseMathTest` (camera azimuth/elevation convention and the ENU→(E,Up,N)
  stitcher axis swap). Pose conventions live in the pure `PoseMath` object —
  add new pure math there so it stays testable without Android or Robolectric.
- Do not unit-test `CaptureViewModel` by mocking `ImageCapture`/CameraX
  end-to-end; instead extract pure logic (`angleDiff`, `angularDist`,
  `CaptureState` derived properties) as the test surface — they are
  already free functions/computed properties, so this needs no
  refactor.

## GoldenCare (React Native)

- Jest is configured (`react-native` preset) but no test files exist.
  `useAuthStore` (pure zustand store, no side effects) is the best first
  target.
