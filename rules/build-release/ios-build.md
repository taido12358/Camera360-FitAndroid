# iOS Build Rules (GoldenCare)

`ios/GoldenCareApp/` is a standard React Native iOS host (has `Info.plist`,
`Podfile`). Standard RN flow applies:

```powershell
cd ios
pod install
cd ..
npm run ios
```

Not verified working in this session — no build was attempted. Confirm
CocoaPods/Xcode are available before assuming this succeeds.
