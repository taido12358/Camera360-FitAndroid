# Build Guide — Camera360

## Stack công nghệ

| Công cụ | Phiên bản | Vai trò |
|---------|-----------|---------|
| Kotlin | 2.0.21 | Ngôn ngữ native Android |
| AGP | 8.6.1 | Android Gradle Plugin |
| Gradle | 8.10.2 | Build system |
| Jetpack Compose BOM | 2024.10.01 | UI toolkit |
| CameraX | 1.4.0 | Camera API |
| JDK | 17 | Java runtime |

---

## Hướng dẫn Build Android (Windows)

### Yêu cầu môi trường

- JDK 17
- Android Studio Hedgehog+
- Android SDK API 35
- Android thiết bị/emulator API 24+

### Build debug APK

```powershell
cd android
.\gradlew.bat assembleDebug
```

APK output: `android\app\build\outputs\apk\debug\app-debug.apk`

### Build release APK

```powershell
cd android
.\gradlew.bat assembleRelease
```

> **Lưu ý:** Release build cần cấu hình keystore. Xem [Sign your app](https://developer.android.com/studio/publish/app-signing).

### Chạy trực tiếp trên thiết bị

```powershell
cd android
.\gradlew.bat installDebug
```

---

## Cấu trúc dependencies

```
androidx.activity:activity-compose        → ComponentActivity + setContent
androidx.compose:compose-bom              → quản lý version tất cả Compose libs
androidx.compose.material3:material3      → UI components
androidx.camera:camera-core/camera2/view  → CameraX
accompanist-permissions                   → runtime permission trong Compose
lifecycle-viewmodel-compose               → viewModel() trong Composable
```

---

## Lỗi thường gặp

| Lỗi | Fix |
|-----|-----|
| `Cannot find symbol: Camera360Theme` | Clean + Rebuild project |
| `CAMERA permission denied` | Chạy app trên thiết bị thật hoặc cấp quyền trong emulator settings |
| `Camera binding failed` | Đóng tất cả app khác đang dùng camera |
| `Kotlin plugin compose version mismatch` | Đảm bảo `kotlin.plugin.compose` cùng version với `kotlin.android` |
