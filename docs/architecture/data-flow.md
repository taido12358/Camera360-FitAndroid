# Data Flow

## Camera360 (native Android)

```
Gyroscope sensor
      │  (azimuth, pitch — smoothed)
      ▼
GyroscopeManager.orientationFlow()
      │
      ▼
CaptureViewModel (StateFlow<CaptureState>)
      │  nearestUncapturedIndex / isAligned drive UI guidance
      ▼
CaptureScreen (Compose) ── shutter press ──▶ CameraX ImageCapture
                                                    │
                                                    ▼
                                     filesDir/frames/frame_%04d.jpg
                                     (+ mirrored to device gallery)
                                                    │
                             (repeat until all 24 frames captured)
                                                    │
                                                    ▼
                                       StitchingEngine.stitch()
                                     (equirectangular projection,
                                      cosine-weighted blend, IO thread)
                                                    │
                                                    ▼
                              filesDir/panorama_<ts>.jpg
                              (+ mirrored to device gallery)
```

All data stays on-device. No network calls, no backend, no analytics.

## GoldenCare (React Native)

No real data flow exists yet:

- `useAuthStore` holds `user`/`isAuthenticated` in memory only — set via
  `login(user)`/`logout()`, called from nowhere yet (no login screen wired
  to the store as of this writing — verify before assuming otherwise).
- No network client, no persistence, no data fetched from any screen.

This section should be rewritten once GoldenCare gains real data
fetching/persistence — do not let it go stale.
