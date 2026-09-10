# Development Workflow

## Camera360 (native Android)

```powershell
cd android
.\gradlew.bat installDebug
```

Requires JDK 17, Android SDK API 35, a device/emulator API 24+ with a
camera (and ideally a rotation-vector sensor — see
docs/modules/camera360-capture.md for behavior without one).

Full build/signing detail: rules/build-release/android-build.md.

## GoldenCare (React Native)

```powershell
npm install
npm run android   # or: npm run ios
```

Metro dev server: `npm run start`. No environment-specific config
(`.env`, API base URL, etc.) exists yet — there is no backend to point at.

## Before starting work

1. Read CLAUDE.md.
2. Read rules/ai-agent/before-coding.md.
3. Read tasks/state/current-state.md and tasks/active/current-task.md.
4. Read the architecture doc for the app you're touching (never both —
   they are unrelated, see docs/architecture/README.md).
