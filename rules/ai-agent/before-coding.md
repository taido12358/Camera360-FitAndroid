# Before Coding

The project is **Camera360** (native Android, panorama capture + stitch —
see docs/project/overview.md). GoldenCare is out of scope
(docs/decisions/ADR-0001-camera360-is-primary-project.md) — only touch it
if a task explicitly names it.

1. Read tasks/state/current-state.md and tasks/active/current-task.md.
2. Read docs/architecture/camera360-app.md and the relevant module doc
   under docs/modules/ (camera360-capture.md / camera360-stitching.md).
3. Read rules/android-native/, plus testing/, security/, and
   architecture/ rules.
4. Do not assume a dependency, config, or wiring exists just because code
   references it — verify against the actual `build.gradle` /
   `AndroidManifest.xml` before building on top of something.
