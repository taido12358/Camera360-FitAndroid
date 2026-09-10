# Image Processing / Stitching Rules

- `StitchingEngine` must stay free of Android `Context`/UI dependencies —
  see rules/architecture/module-boundaries.md.
- Any change to `MAX_FRAME_LONG_SIDE`, `OUT_W`/`OUT_H` must consider peak
  memory: the engine holds all decoded frame pixel arrays plus the full
  output `IntArray` (`OUT_W * OUT_H`) in memory simultaneously. Raising
  output resolution or per-frame resolution without testing on a
  low/mid-range device risks `OutOfMemoryError`.
- Stitching runs on `Dispatchers.IO`, not a background service — a
  long-running stitch will be killed if the process dies (e.g. user
  swipes the app away). No work-manager/foreground-service safety net
  exists yet; treat this as a known limitation, not something to silently
  "fix" by changing dispatcher.
- Do not add a third-party CV/stitching library (e.g. OpenCV) without an
  ADR — the current approach is deliberately dependency-free.
