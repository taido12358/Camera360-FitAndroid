# Architecture Index

This repo's architecture is **Camera360's architecture** — see
docs/decisions/ADR-0001-camera360-is-primary-project.md. GoldenCare
(React Native) is a separate, out-of-scope app kept for reference only.

- Camera360 (native Android, the project): → camera360-app.md
- Data flow (capture → stitch → gallery): → data-flow.md
- Module-level docs: → ../modules/README.md
- Architecture-level rules: → ../../rules/architecture/README.md
- GoldenCare (out of scope, reference only): → goldencare-app.md

## Why GoldenCare is documented but out of scope

This repo's git history started with both apps present under one root,
with no shared code between them. On 2026-09-10 the user confirmed the
project's actual purpose (multi-angle capture + panorama stitching),
which matches Camera360 and not GoldenCare — see ADR-0001. GoldenCare's
docs are kept only so a future session doesn't have to rediscover what it
is if the user ever asks about it again.
