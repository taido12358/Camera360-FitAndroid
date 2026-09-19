# Current State

## Project Phase

Camera360 works end to end on the emulator (guided mode) and in the accelerometer-only
"manual mode" pipeline on synthetic ground truth and on the real test phone's
hardware (timings, memory, sensors). **Not yet verified with real overlapping photos
shot by a person on the test phone** - that is the one thing still missing.

## What this project is

Camera360: capture a scene from every angle with the phone camera, stitch all
captures into one panorama, entirely on-device. See docs/project/overview.md and
docs/project/requirements.md.

## Implemented

- **Guided mode** (phones with a rotation-vector sensor): 24-frame gyroscope-driven
  capture, AR dot overlay (respects roll), auto-capture on sustained alignment;
  sensor poses are sharpened by image registration (`PoseRefinement`, trusted
  photos only, safe fallback); pose-driven rendering.
- **Manual mode** (accelerometer only, e.g. the Galaxy A12 test phone; ADR-0002):
  free-form shooting with live tilt/roll/steadiness feedback, gravity for pitch/roll,
  heading by image registration (`YawRegistration`: NCC, voting placement, triangle
  filter, robust solve, pitch correction, FOV self-calibration), coverage advice,
  crop to covered area, per-session diagnostics file.
- **Renderer** (`EquirectRenderer`, pure): frame culling, bilinear sampling,
  exposure/white-balance gain compensation, blending.
- In-app panorama viewer, safe "open in gallery" by Uri, keep-screen-on, portrait
  lock, EXIF rotation, storage cleanup, OOM/IO error handling.

## Verified

- 68 JVM unit tests (pose math, gain compensation, registration incl. real-photo
  texture / exposure drift / noise / 30-photo sweep / random false-edge graphs,
  renderer vs ground truth, end-to-end manual and guided-refinement pipelines,
  crop/coverage/gravity/diagnostics) - all passing.
- On the Galaxy A12 (instrumented tests): registration of 30 photos ~9 s, stitching
  30 real-size (12 MP) photos ~13 s, peak Java heap 124 of 256 MB; real capture =
  4000x3000 JPEG, EXIF orientation 6; measured FOV 69.6 deg matches the hardware
  specs; accelerometer clean.
- Emulator: full guided flow, viewer, Back handling, gallery open. Two independent
  code-review rounds; all real findings fixed.

## Known Issues / limits

- Manual mode quality on real photos unknown until a person shoots a sweep.
- Low-texture / repetitive scenes (plain walls, hazy windows, checkerboards) give weak
  or wrong pair matches; the solver drops or occasionally misplaces a few photos.
- Emulator's virtual camera returns the same image for every pose: it cannot judge
  seam/overlap quality.
- No CI/CD; `connectedAndroidTest` uninstalls the app from the device afterwards.

## Next Priority

Real overlapping photos from the Galaxy A12 (pull `files/frames/manual_*.jpg`, the
panorama and `files/last_session_diagnostics.txt` with `adb run-as`), then tune on
real data. Later: multi-band blending, export/sharing, sphere-coverage guidance.
