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

### Open requirement questions (not yet confirmed)

- Is 24 frames (3×8 grid) the intended final coverage, or should it be
  configurable / denser / sparser?
- Is a fixed 65° assumed horizontal FOV (`StitchingEngine.CAMERA_HFOV_DEG`)
  acceptable long-term, or should it be derived per-device/per-lens?
- Should panoramas ever leave the device (export, share, cloud backup)?
- Any target output format beyond JPEG (e.g. proper 360° metadata for
  viewers that expect an XMP panorama tag)?

Record answers to these as they're confirmed — update this file directly
rather than letting them live only in conversation.

## GoldenCare (React Native) — out of scope

Per docs/decisions/ADR-0001-camera360-is-primary-project.md, GoldenCare is
not part of this project going forward. No requirements are tracked for
it here. Its code remains in the repo but should be treated as
legacy/inactive unless the user explicitly reintroduces it.
