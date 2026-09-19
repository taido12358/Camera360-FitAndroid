# Current State

## Project Phase

FOUNDATION -> Camera360 capture/stitching working end to end on the emulator;
manual (no-sensor) mode implemented for the real test phone but not yet
verified with real photos.

## What this project is

Camera360: capture a scene from every angle with the phone camera, stitch all
captures into one panorama, entirely on-device. See docs/project/overview.md and
docs/project/requirements.md.

## Implemented

- Guided mode (devices with a rotation-vector sensor): 24-frame gyroscope-driven
  capture, AR dot overlay (respects roll), auto-capture on sustained alignment,
  pose-driven equirectangular stitching (parallel, bilinear, exposure/WB gain
  compensation). Verified end to end on the emulator.
- Manual mode (devices with only an accelerometer, e.g. the Galaxy A12 test
  phone): free-form shots, gravity pitch/roll, heading from image registration,
  crop to covered area. See tasks/active/current-task.md.
- Portrait-locked activity; EXIF rotation applied when stitching.
- 36 JVM unit tests + 1 on-device benchmark test.

## Verified

Guided mode + emulator: yes (emulator's virtual camera returns the same image for
every pose, so seam/overlap quality is NOT assessable there).
Manual mode: algorithm verified against synthetic ground truth and on-device
timing (9 s for 30 photos on the A12); real-photo behaviour NOT verified.

## Known Issues

- Low-texture / repetitive scenes (plain walls, hazy windows, checkerboards) give
  weak or wrong pair matches; the solver drops or misplaces a few photos there
  (see docs/modules/camera360-stitching.md).
- Manual mode result quality unknown until tested with real photos.
- No CI/CD; connectedAndroidTest uninstalls the app from the device afterwards.

## Next Priority

Get real overlapping photos from the Galaxy A12 (user rotates the phone), inspect
the panorama, and tune registration/blending on real data. Then: per-photo pitch
correction from registration residuals, better blending (multi-band), export.
