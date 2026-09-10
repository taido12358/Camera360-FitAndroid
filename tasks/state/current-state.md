# Current State

## Project Phase

FOUNDATION → first real feature work landed 2026-09-10 (TASK-003), not
yet verified on a device.

## What this project is

Camera360: capture a scene from every angle with the phone camera, stitch
all captures into one complete panorama, entirely on-device. See
docs/project/overview.md and docs/project/requirements.md.

## Implemented

- Camera360: 24-frame guided capture (gyroscope-driven), AR-style
  world-fixed dot overlay (`SphereGuideOverlay`), auto-capture on
  sustained alignment, and on-device equirectangular stitching using each
  frame's full captured rotation matrix + a runtime-measured camera FOV.
  **Not build-verified or device-tested this session** (no network access
  to fetch Gradle/dependencies here) — see
  logs/ai-agent/sessions/2026/09/2026-09-10-session-003.md.

## Scaffolded

- Knowledge system: rules/, docs/, tasks/, logs/.

## Verified

None — no automated tests exist for Camera360, and TASK-003's changes
have not been run on a device yet (see docs/workflow/testing.md and
tasks/active/current-task.md).

## Known Issues (Camera360)

- No handling surfaced to the UI when a device lacks a gyroscope
  (`GyroscopeManager.hasGyroscope == false`) — auto-capture and stitching
  both silently degrade in that case. See
  docs/modules/camera360-capture.md.
- No automated tests, no CI/CD pipeline.
- TASK-003 changes are unverified — must be built and manually QA'd on a
  real device before considering this "done" (see
  rules/testing/manual-qa.md).

## Out of scope (not issues to fix)

GoldenCare's known inconsistencies (unwired navigator, undeclared deps,
RN/Android package mismatch) are no longer active concerns per ADR-0001 —
left as-is unless GoldenCare is reintroduced.

## Next Priority

Build and manually test TASK-003 on a real device (full 24-frame capture,
including a deliberately tilted/rolled shot to confirm the rotation-matrix
stitching fix), then resolve the remaining open questions in
docs/project/requirements.md (frame count/coverage, export/sharing,
output format, alignment/hold-time tuning).
