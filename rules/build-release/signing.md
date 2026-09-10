# Signing Rules

- No release keystore exists in this repo (correct — it never should be
  committed).
- Camera360 release build (`assembleRelease`) currently has no signing
  config in `android/app/build.gradle` — it will produce an unsigned APK.
  Configuring signing is required before any real release; see
  https://developer.android.com/studio/publish/app-signing.
- iOS signing (GoldenCare) not evaluated — standard Xcode/Apple Developer
  signing flow applies, nothing project-specific is configured yet.
- If a keystore/signing config is added, ensure passwords/paths come from
  environment variables or a git-ignored `local.properties`-style file,
  never hardcoded in `build.gradle`.
