# ADR-0002: Support phones without a rotation-vector sensor via gravity + image registration

## Status

ACCEPTED (2026-09-20)

## Context

Camera360's original pipeline needs a per-photo device orientation from
`TYPE_ROTATION_VECTOR` (gyroscope + magnetometer fusion). The user's real test
phone, a Samsung Galaxy A12 (SM-A127F), has **only an accelerometer** (verified
with `dumpsys sensorservice`: no gyroscope, no magnetic-field, no rotation-vector
sensor of any kind). Such phones are common at the low end. On it the app could
only take photos and refuse to stitch.

Options considered:

1. **OpenCV `Stitcher`** (feature matching + bundle adjustment). Rejected: the
   Android Maven artifact (`org.opencv:opencv:4.10.0`, checked by listing the AAR)
   ships Java bindings for core/imgproc/video/objdetect/photo/ml/dnn/videoio/
   imgcodecs only - no `stitching`, `features2d`, `calib3d`. Using it would mean
   writing NDK/C++, plus ~20-50 MB of native libraries per ABI.
2. **Pure feature-based pipeline written from scratch** (ORB/SIFT, homographies,
   bundle adjustment). Large, and unnecessary: the missing information is small.
3. **Gravity for pitch/roll + image registration for heading (chosen).** The
   accelerometer gives every photo's pitch and roll exactly; the only unknown is
   heading (yaw). Photos are warped into a gravity-levelled azimuth/elevation
   frame where the yaw difference between two photos is a plain horizontal
   shift, found by normalised cross-correlation and solved on the pair graph.
   The existing pose-driven renderer is reused unchanged.

## Decision

Add **manual mode**, active when no rotation-vector sensor exists: free-form
shooting with live tilt/roll/steadiness feedback from the accelerometer;
stitching = `YawRegistration` (NCC pair registration, voting placement, triangle
loop-consistency filter, Cauchy-IRLS heading solve, per-photo pitch correction,
field-of-view self-calibration from loop closure) feeding the pure
`EquirectRenderer` (gain compensation, blending, coverage crop). Guided
24-frame mode remains for phones that do have the sensor.

Robustness choices made from measurements, not assumptions (see
docs/modules/camera360-stitching.md): local contrast normalisation (exposure),
vertical-offset search (gravity error), consistency pruning that drops photos
rather than placing them wrongly (repetitive / low-texture scenes), and
re-run-validated FOV calibration (a 6-12 % FOV error otherwise ruins the result).

## Consequences

- No native dependencies; everything is Kotlin and unit-testable on the JVM
  (60 tests incl. ground-truth synthetic worlds) plus on-device benchmarks.
- Accuracy is bounded by accelerometer gravity (~0.5-1 deg), rigid rotation about
  the camera (no parallax) and scene texture. Plain walls give no links; the app
  drops unlinkable photos and says so.
- **Not yet verified with real overlapping photos on the phone** (needs the user
  to rotate the phone while shooting).
- Measured on the Galaxy A12: registration of 30 photos ~9 s, render ~13 s,
  peak Java heap 124 MB of 256 MB.
