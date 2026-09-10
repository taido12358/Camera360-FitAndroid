# Manual QA Rules

## Camera360 (native Android)

Before merging any change to capture or stitching, manually verify on a
real device (emulator gyroscope data is unreliable):
- All 24 frames can be captured guided by the on-screen direction hints.
- Stitching completes and produces a viewable equirectangular JPEG with no
  gross misalignment at row/column seams.
- Both individual frames and the final panorama appear in the device
  gallery (`DCIM/Camera360`).
- Camera permission denial is handled without a crash.

## GoldenCare (React Native)

Before merging a navigation or auth-state change, manually verify:
- The app does not crash on cold start.
- Tab navigation (once `AppNavigator` is actually wired to `App.tsx`)
  switches screens without state loss where expected.
