# Module: Camera360 Stitching Engine

File: `StitchingEngine.kt`. Pure Kotlin object, no Android UI dependency —
safe to unit test in isolation (see rules/testing/unit.md).

## Public surface

- `StitchingEngine.stitch(inputs: List<FrameInput>, outputFile: File,
  onProgress: (Float) -> Unit)` — suspend function, runs on
  `Dispatchers.IO`. Requires exactly the frames the caller collected; it
  does not validate frame count or coverage — that check lives in
  `CaptureViewModel.stitchPanorama` (`inputs.size < s.totalFrames`).

## Known constants that affect output quality/cost

- `CAMERA_HFOV_DEG = 65.0` — assumed, not measured per-device. Wrong FOV
  produces visible seams/ghosting. See
  docs/architecture/camera360-app.md#constraints-worth-knowing-before-changing-this-code.
- `OUT_W/OUT_H = 3840×1920` — fixed output size regardless of input
  resolution.
- `MAX_FRAME_LONG_SIDE = 1024` — frames are downsampled before stitching;
  raising this improves detail but increases peak memory roughly
  quadratically.

## Gotchas

- `stitch()` throws `IllegalStateException` if every frame fails to
  decode — callers must catch this (`CaptureViewModel` does, via
  `stitchError`).
- The algorithm has no feature matching/alignment correction — stitching
  quality is entirely dependent on the accuracy of the gyroscope pose
  recorded per frame (`capturedAzimuth`/`capturedPitch` in
  `CaptureViewModel`). Do not "fix" seams by changing this engine before
  confirming the pose data is the actual source of the seam.
