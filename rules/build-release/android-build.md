# Android Build Rules

## Camera360 (the actual `android/` project)

```powershell
cd android
.\gradlew.bat assembleDebug     # debug APK
.\gradlew.bat installDebug      # build + install on connected device
.\gradlew.bat assembleRelease   # release APK — needs signing, see signing.md
```

Requires JDK 17, AGP 8.6.1, Gradle 8.10.2, compileSdk/targetSdk 35,
minSdk 24. Debug APK output:
`android\app\build\outputs\apk\debug\app-debug.apk`.

**Important:** because `android/` is Camera360's own native project, *not*
GoldenCare's RN Android host, `npm run android` (the RN CLI command) does
not apply here — there is currently no standard RN Android build path for
GoldenCare in this repo. Confirm this with the user before assuming
`npm run android` works.

## Common errors

| Error | Fix |
|---|---|
| `Cannot find symbol: Camera360Theme` | Clean + rebuild |
| `CAMERA permission denied` | Run on a real device, or grant camera permission in emulator settings |
| `Camera binding failed` | Close other apps using the camera |
| Kotlin/Compose plugin version mismatch | Ensure `kotlin.plugin.compose` matches `kotlin.android` version |
