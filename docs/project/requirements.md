# Product Requirements

## Camera360 (native Android) — the project, confirmed 2026-09-10

Confirmed directly by the user: the app must let a user capture a scene
from **every angle** using the phone's camera, then **stitch all captured
images into one complete panorama photo**.

As currently implemented, this means:

- Guide the user through capturing a fixed set of frames covering the
  full sphere around them (today: 24 frames — 3 pitch rows × 8 azimuth
  columns), using the device gyroscope to track orientation and tell the
  user which direction to point next.
- Record the device's actual orientation at the moment each frame is
  captured (not just the nominal target), since stitching accuracy
  depends on the real pose.
- Once every frame is captured, stitch them into a single equirectangular
  panorama image entirely on-device (no server round-trip, no third-party
  CV library — see docs/architecture/camera360-app.md).
- Save both the individual frames and the final panorama to the device
  gallery, in addition to internal app storage.

Also confirmed 2026-09-10 (same session): capture must show the target
points "in space" (world-fixed AR-style markers over the live preview —
implemented as `SphereGuideOverlay`, pre-existing) and must fire the
shutter automatically once the phone rotates into the correct position
(implemented 2026-09-10: `CaptureViewModel.startAutoCapture`), not rely on
the user judging alignment and tapping manually. Stitching accuracy was
also raised explicitly — addressed by using each frame's full captured
rotation matrix (not azimuth/pitch alone) and the camera's real measured
FOV instead of a hardcoded constant; see
docs/architecture/camera360-app.md.

**Not yet verified**: none of this has been build-tested or run on a
device in this session (no network available to fetch Gradle/dependencies
here) — status is IMPLEMENTED, not TESTED/VERIFIED. See
rules/testing/manual-qa.md before treating it as working.

### Open requirement questions (not yet confirmed)

- Is 24 frames (3×8 grid) the intended final coverage, or should it be
  configurable / denser / sparser?
- Should panoramas ever leave the device (export, share, cloud backup)?
- Any target output format beyond JPEG (e.g. proper 360° metadata for
  viewers that expect an XMP panorama tag)?
- Is the current alignment threshold (10°) and auto-capture stable-hold
  window (350ms) the right feel, or does it need tuning after real-device
  testing?

Record answers to these as they're confirmed — update this file directly
rather than letting them live only in conversation.

## GoldenCare (React Native) — out of scope

Per docs/decisions/ADR-0001-camera360-is-primary-project.md, GoldenCare is
not part of this project going forward. No requirements are tracked for
it here. Its code remains in the repo but should be treated as
legacy/inactive unless the user explicitly reintroduces it.
