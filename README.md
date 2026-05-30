# Camera360

Ứng dụng camera Android thuần native sử dụng **Jetpack Compose** + **CameraX**, hỗ trợ chụp nhiều frame liên tiếp và lưu vào internal storage.

## Cấu trúc dự án

```
Camera360/
└── android/
    ├── app/
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/camera360/
    │       │   ├── MainActivity.kt          # Entry point — Compose host
    │       │   ├── CaptureViewModel.kt      # ViewModel + StateFlow
    │       │   └── ui/
    │       │       ├── CaptureScreen.kt     # Màn hình chụp ảnh
    │       │       └── theme/
    │       │           └── Theme.kt         # Material3 dark theme
    │       └── res/values/
    │           ├── strings.xml
    │           └── styles.xml
    ├── build.gradle
    ├── settings.gradle
    └── gradle.properties
```

## Yêu cầu môi trường

- JDK 17
- Android Studio Hedgehog hoặc mới hơn
- Android SDK API 35
- Thiết bị/Emulator API 24+

## Build & chạy

```powershell
cd android
.\gradlew.bat assembleDebug
```

APK output: `android\app\build\outputs\apk\debug\app-debug.apk`

Hoặc mở project trong Android Studio và nhấn **Run**.

## Tính năng

| Tính năng | Mô tả |
|-----------|-------|
| Preview full-screen | CameraX PreviewView chiếm toàn màn hình |
| Chụp ảnh | ImageCapture use case — nút shutter tròn ở dưới |
| Lưu frame | Mỗi ảnh lưu dạng JPEG vào `filesDir` (internal storage) |
| Frame counter | Hiển thị `X/24 frames` ở đầu màn hình |
| Permission flow | Yêu cầu quyền CAMERA lúc runtime bằng Accompanist |

## Stack công nghệ

| Thư viện | Phiên bản | Vai trò |
|----------|-----------|---------|
| Kotlin | 2.0.21 | Ngôn ngữ |
| Jetpack Compose BOM | 2024.10.01 | UI framework |
| CameraX | 1.4.0 | Camera preview + capture |
| Accompanist Permissions | 0.36.0 | Runtime permission in Compose |
| ViewModel + StateFlow | lifecycle 2.8.7 | State management |
| AGP | 8.6.1 | Build system |
