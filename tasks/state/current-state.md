# Current State

## Project Phase

FOUNDATION — project scope confirmed 2026-09-10: Camera360 (panorama
capture app) is the project; GoldenCare is out of scope. See
docs/decisions/ADR-0001-camera360-is-primary-project.md.

## What this project is

Camera360: capture a scene from every angle with the phone camera, stitch
all captures into one complete panorama, entirely on-device. See
docs/project/overview.md and docs/project/requirements.md.

## Implemented

- Camera360: 24-frame guided capture (gyroscope-driven) + on-device
  equirectangular stitching (functional, unverified by automated tests).

## Scaffolded

- Knowledge system: rules/, docs/, tasks/, logs/.

## Verified

None — no automated tests exist for Camera360 (see
docs/workflow/testing.md).

## Known Issues (Camera360)

- `CAMERA_HFOV_DEG` is a hardcoded assumed FOV, not derived per-device —
  see docs/project/requirements.md open questions.
- No handling surfaced to the UI when a device lacks a gyroscope
  (`GyroscopeManager.hasGyroscope == false`) — see
  docs/modules/camera360-capture.md.
- No automated tests, no CI/CD pipeline.

## Out of scope (not issues to fix)

GoldenCare's known inconsistencies (unwired navigator, undeclared deps,
RN/Android package mismatch) are no longer active concerns per ADR-0001 —
left as-is unless GoldenCare is reintroduced.

## Next Priority

Resolve the open requirement questions in docs/project/requirements.md
(frame count/coverage, FOV handling, export/sharing, output format), then
file the first real Camera360 task under tasks/active/.
