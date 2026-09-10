# Project Overview

## What this project is

**A 360° panorama camera app.** The user captures a scene from every
angle using the phone's camera, guided by the device's orientation
sensors, and the app stitches every captured frame into one complete
panorama photo — entirely on-device.

This is implemented today as **Camera360**, a standalone native Android
app (Kotlin + Jetpack Compose + CameraX) at
`android/app/src/main/java/com/camera360/`. See
docs/architecture/camera360-app.md for how it works and
docs/project/requirements.md for the confirmed requirements.

Decision record: docs/decisions/ADR-0001-camera360-is-primary-project.md.

## Repository shape (still true, for context)

This repo also contains **GoldenCare**, an unrelated React Native
elder-care app shell (`App.tsx`, `index.js`, `src/`) that shares no code
with Camera360. Per ADR-0001, **GoldenCare is out of scope** for this
project — its code is left in place but is not an active development
target. Do not read its docs/rules unless a task explicitly names it. See
docs/architecture/goldencare-app.md if that ever changes.

## Camera360 (native Android) — the project

- Current state: functional capture flow (24-frame spherical grid guided
  by gyroscope) and a custom equirectangular stitching engine — see
  `android/app/src/main/java/com/camera360/StitchingEngine.kt`. No feature
  matching; stitching relies on known camera poses from the gyroscope.
- See docs/architecture/camera360-app.md and docs/architecture/data-flow.md.

## What does not exist yet

- No backend, no API, no database, no auth server.
- No CI/CD pipeline (no `.github/` workflows found).
- No automated tests beyond default scaffolding.
- No cloud sharing/export of panoramas — everything is on-device only.

Do not create documentation, rules, or infrastructure implying otherwise.
