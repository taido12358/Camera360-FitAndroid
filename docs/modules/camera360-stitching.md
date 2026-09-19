# Module: Camera360 Stitching Engine

File: `StitchingEngine.kt`. Pure Kotlin object, no Android UI dependency —
safe to unit test in isolation (see rules/testing/unit.md).

## Public surface

- `StitchingEngine.stitch(inputs: List<FrameInput>, outputFile: File,
  hFovDeg: Double = CAMERA_HFOV_DEG, onProgress: (Float) -> Unit)` —
  suspend function, runs on `Dispatchers.IO`. Requires exactly the frames
  the caller collected; it does not validate frame count or coverage —
  that check lives in `CaptureViewModel.stitchPanorama`
  (`inputs.size < s.totalFrames`). Callers should pass the camera's
  measured FOV (`CaptureState.measuredHFovDeg`) rather than relying on the
  default fallback constant.
- `FrameInput(file: File, rotationMatrix: FloatArray)` — `rotationMatrix`
  is the 9-float device→world rotation matrix captured at shutter press
  (see `CaptureViewModel.FrameTarget.capturedRotationMatrix`), **not**
  azimuth/pitch. This is what makes stitching accurate even if the phone
  was rolled/tilted between shots — see
  docs/architecture/camera360-app.md.

## Known constants/inputs that affect output quality/cost

- `hFovDeg` (parameter, defaults to `CAMERA_HFOV_DEG = 65.0`) — prefer the
  value CaptureScreen measures from `CameraCharacteristics` at runtime;
  the constant is only a fallback. Wrong FOV produces visible
  seams/ghosting.
- `OUT_W/OUT_H = 3840×1920` — fixed output size regardless of input
  resolution.
- `MAX_FRAME_LONG_SIDE = 1024` — frames are downsampled before stitching;
  raising this improves detail but increases peak memory roughly
  quadratically.

## Gotchas

- `stitch()` throws `IllegalStateException` if every frame fails to
  decode — callers must catch this (`CaptureViewModel` does, via
  `stitchError`).
- Camera basis (`right`/`up`/`fwd`) is derived from `rotationMatrix` via
  `cameraBasisFromRotationMatrix`, which assumes the back camera lens
  points along the device's `-Z` axis (standard orientation-sensor
  convention: device Z points out of the screen face). If a future change
  targets the front camera or a device with a different sensor mounting,
  this assumption must be revisited.
- The algorithm still has no feature matching/alignment correction beyond
  using the true captured pose — stitching quality now depends on (a) the
  rotation matrix being accurate (it is, since it comes straight from the
  system's sensor fusion) and (b) `hFovDeg` matching the real lens. Do not
  "fix" seams by tweaking the blend weights before checking those two
  first.

## Renderer: parallel + bilinear (2026-09-19)

`StitchingEngine.stitch` renders output rows in parallel (interleaved row
sets across up to 8 `Dispatchers.Default` workers, see `renderRow`), samples
source frames bilinearly instead of nearest-pixel, and reuses per-column
sin/cos tables. On the emulator this took the 24-frame stitch from ~50 s to
~26 s. Output pixels are identical in geometry; only sampling smoothness
changed. `onProgress` is now called from worker threads (still safe for a
StateFlow), so values may arrive very slightly out of order.

## EXIF rotation + FOV semantics (2026-09-19, found on emulator)

- CameraX saves JPEGs as **sensor-oriented pixels + an EXIF rotation tag**
  (emulator: 1280x960 landscape, orientation 6). `BitmapFactory` ignores the
  tag, so the stitcher used to lay frames in *sideways*. `StitchingEngine`
  now reads `ExifInterface` and rotates each frame upright before use.
- `hFovDeg` (and `CaptureState.measuredHFovDeg`) means the FOV along the
  sensor's **long side**, exactly what `CameraCharacteristics` gives
  (physical sensor width / focal length). Per frame the engine derives
  `focalPx = (longSidePx/2)/tan(fov/2)` and true rectilinear half-FOV
  tangents for both axes (the old code scaled the *angle* by aspect ratio,
  which is wrong for wide lenses).
- The AR overlay uses the same value via `previewFocalLengthPx`: the 4:3
  preview is shown FILL_CENTER, so on tall phone screens the long side maps
  to the screen *height*, not width.
- Emulator caveat: the emulator's reported sensor size/focal length gives an
  unrealistically wide FOV (~104°), so overlay dot spacing and overlap on the
  emulator are not representative; real devices report real values.

## Exposure / white-balance gain compensation (2026-09-19)

Phones re-meter AE/AWB per shot, so neighbours can differ in brightness or
tint. `StitchingEngine.estimateGains` samples the sphere on a 360x180 grid,
and for every pair of frames that both see a direction well inside their
frame (blend weight >= 0.25, to dodge vignetting) accumulates the overlap
statistics. `GainCompensation.solve` (Brown & Lowe 2007, sec. 6; pure Kotlin,
JVM-tested in `GainCompensationTest`) then finds one gain per frame and colour
channel minimising the overlap mismatch, with a prior pulling gains to 1
(sigma_g = 0.25), clamped to [0.6, 1.7] and re-centred on a mean of 1 so the
panorama cannot get globally darker/brighter. Frames with no overlap keep 1.0.

Verification status: solver unit-tested; runs end-to-end on the emulator
(38 s, no errors). NOT visually verified on real overlapping data — the
emulator returns the same image for every pose, which makes the overlaps
geometrically inconsistent (it produced a darker panorama, an artefact of
that data). Check on a real device that seams between shots visibly even out.

## Accelerometer-only pose reconstruction (`YawRegistration`, 2026-09-20)

For devices with no gyroscope/magnetometer. Gravity gives pitch/roll; only
each photo's heading is unknown.

1. Every photo is warped into a gravity-levelled (azimuth, elevation) frame
   where it initially looks at azimuth 0 (`PoseMath.rotationFromGravity(up, 0)`).
   If photo j was turned by delta = heading_j - heading_i relative to photo i,
   the scene at azimuth a in j appears at a + delta in i, so delta is a pure
   horizontal shift.
2. For every pair, coarse circular search (2 deg steps, high-passed luma so
   exposure/vignetting drop out, normalised cross-correlation on the overlap),
   then 0.25 deg refinement. Pairs need >= ~12x12 deg overlap and NCC >= 0.45.
3. Headings solve: maximum-weight spanning tree from the largest linked group
   for the initial estimate, then weighted least squares over *all* pairs with
   Cauchy IRLS (scale 3 deg) so a false match cannot drag the chain, and the
   360 deg loop closes by spreading the error.

Verified on the JVM against a synthetic textured sphere with known poses
(`YawRegistrationTest`: level/rolled/noisy gravity, negative direction, full
circle, shuffled capture order, outlier pairs, unrelated/non-overlapping
photos, stray first photo). NOT yet verified with real photos on the phone —
needs textured scenes (blank walls have nothing to correlate).
Limits: assumes a rigid camera rotation (no parallax), a correct FOV, and
accelerometer gravity within ~1 deg (hold still when shooting).

### Registration robustness (2026-09-20, from realistic tests)

Tests with a real room photo wrapped on a sphere + exposure drift (0.8-1.2x) +
sensor noise + 0.4 deg gravity noise exposed three weaknesses, all fixed:

- **Exposure / white balance**: photos are contrast-normalised before
  correlation (local mean removed, divided by local std + floor 6), so AE
  re-metering no longer changes the score (same poses: exposure-drift NCC
  identical to clean).
- **Gravity error**: 0.4 deg of accelerometer noise alone dropped a true pair's
  NCC from 0.68 to 0.35, because the match assumed perfect levelling. The
  refinement now also searches a +-2 deg vertical offset (coordinate descent),
  restoring those pairs to 0.85-0.9.
- **Weak / repetitive matches**: accept threshold is 0.40, but a match below 0.60
  must beat the best *different* shift (outside +-8 deg) by >= 0.08, so
  repetitive textures do not produce false links. False pairs measured <= 0.31.

Known limit: plain, low-detail walls (little for correlation to lock onto) give
weak or no links; the app then drops unlinkable photos with a notice.

### Graph solve: voting + consistency check (2026-09-20)

Measured on a three-row sweep of a real room photo (with a checkerboard TV and
a hazy window): ~30 % of pair matches were wrong, some with NCC 0.8, and
neither NCC nor peak margin separated right from wrong. So the solver no longer
trusts a spanning tree of best matches:

1. **Voting placement** - seed with the single most confident pair, then
   repeatedly place the photo whose already-placed neighbours agree best
   (proposals within 3.5 deg cluster; needs >= 2 agreeing edges or one edge
   with NCC >= 0.40).
2. **Robust LS refinement** over all edges (Cauchy IRLS, closes 360 deg loops).
3. **Consistency prune** - a photo with < 50 % (by weight) of its edges within
   4 deg of the solution is dropped and the rest re-solved; dropped photos are
   reported to the user instead of being placed wrongly.

Result on that sweep: 24/30 photos within 1 deg; the rest (in the featureless
window) are dropped or misplaced. Tightening the prune thresholds also dropped
good photos elsewhere, so 4 deg / 50 % is the compromise. Real-device data is
needed to tune further. Shooting advice for the UI: step ~25-30 deg (a portrait
photo is only ~52 deg wide on the short side), prefer textured scenes.

### Crop to covered area (2026-09-20)

`StitchingEngine.stitch(..., cropToContent = true)` (used by manual mode) crops
the equirectangular render to what photos actually covered:
`CoverageCrop.bounds` (pure, JVM-tested) finds the row band and the smallest
*circular* column range (azimuth wraps, so the crop can straddle the seam) that
contain all covered pixels, ignoring a few stray fringe pixels. Uncovered pixels
are marked internally (alpha 0) and only turned black at the end. Guided
24-frame mode keeps the full 2:1 frame (standard for 360 photo viewers).
Verified on the emulator (temporarily enabled for the guided path: 3840x1869).

### Performance on the real test phone (2026-09-20)

Measured with an instrumented test on the Galaxy A12
(`YawRegistrationDeviceTest`, run `./gradlew connectedDebugAndroidTest` with the
phone attached; logs per-phase timings under tag `YawRegDevice`):
30 photos (3 rows, 435 pairs) registered in **21.9 s -> 9.0 s** after
optimisation. Phases (after): prep 0.24 s, project 0.23 s, coarse match 1.6 s,
cells 0.7 s, refine 5.9 s (154 pairs), solve 0.1 s. Changes: direction vectors
and yaw/elevation rotation formulas instead of sin/cos per sample; refinement
runs on a 1/4 sparse cell set, full set only for the last polish steps; coarse
match skips shifts whose azimuth footprints cannot intersect. The same JVM
workload takes ~2 s, i.e. this phone is ~4-5x slower than the dev machine.

### Triangle (loop-consistency) filter (2026-09-20)

Suggested by an independent review (Gemini, consulted via Chrome as the
project owner asked) of the false-match problem; SIFT/ORB matching and
GNC-TLS were noted but not adopted (Android OpenCV has no features2d Java
bindings; the existing IRLS + consistency prune covers most of what GNC offers).
`YawRegistration.filterByTriangles` runs before the graph solve: for every
triangle of photos with all three edges, relative headings must close to
within 3 deg. A closing triangle supports all three edges; an inconsistent one
blames only its **weakest-NCC** edge (blaming all three condemned good edges).
Edges with blame and no support are dropped; edges in no triangle (chain
neighbours) cannot be checked and are kept. On the hard three-row sweep this
raised accurate photos 24 -> 26 of 30 and placed photos ~24 -> 28.

### Per-photo pitch correction (2026-09-20)

The refinement's vertical-offset search (see robustness notes) used to be thrown
away: stitching trusted gravity pitch absolutely although accelerometer +
hand tremor are only good to ~0.5-1 deg. Now each pair keeps `verticalDeg`
(b_i - b_j, where b_p = how much higher photo p's gravity puts the scene than
truth); `YawRegistration.solve` solves the per-photo biases over the
heading-consistent edges (weighted LS, mean removed since only relative bias is
observable, capped at 3 deg) and `poses(frames, headings, pitchOffsets)` tilts
each pose by -b_p via `PoseMath.tiltElevation` (sign pinned by a unit test).
Test with injected +-1.5 deg biases: mean pitch error 0.56 -> 0.14 deg.

## Renderer split + quantitative ground-truth tests (2026-09-20)

The pure inverse-projection renderer now lives in `EquirectRenderer` (no Android
dependency: bit ops instead of `Color`, plain threads instead of coroutines);
`StitchingEngine` only decodes (EXIF-upright), calls it, crops and encodes.
`EquirectRendererTest` photographs a coloured textured synthetic sphere with
virtual cameras at known poses (3 rows x 10 photos, 240x320) and compares the
rendered 720x360 panorama with the true sphere per pixel:

- exact poses: RMS colour error **1.03 / 255** (geometry verified end to end;
  a mirrored-pose control renders > 15, so the test can fail);
- exposure drift +-22 % per channel: RMSE **13.95 -> 4.75** with gain
  compensation (previously only eyeballed);
- blend sharpness `blendPower` with 0.6 deg yaw error: 0.5 -> 2.49, 1 -> 2.61,
  2 -> 2.76, 4 -> 2.89 (softer hides small pose errors slightly better; kept 1.0).

### End-to-end manual pipeline test (2026-09-20)

`ManualPipelineTest`: 30 hand-held-style photos (jittered spacing, +-1 deg
accelerometer pitch error, +-15 % per-channel exposure drift) of a coloured
textured sphere -> `YawRegistration` -> poses -> `EquirectRenderer` -> per-pixel
comparison with the true sphere. Result: 30/30 photos placed; RMSE **7.59 ->
5.11 / 255** with the pitch correction (exact poses give ~1).
