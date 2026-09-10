# Current Blockers

No active blockers on documentation work.

## Resolved (2026-09-10)

- ~~Is `android/` supposed to be GoldenCare's RN Android host, or is
  Camera360 intentionally a separate standalone app?~~ Resolved:
  Camera360 is its own product; see
  docs/decisions/ADR-0001-camera360-is-primary-project.md.
- ~~Should `App.tsx` render `AppNavigator`?~~ Moot — GoldenCare is out of
  scope.
- ~~Should this repo be split into two repos?~~ Not decided, but no
  longer urgent — GoldenCare is inactive, not actively developed
  alongside Camera360.

## Open (need a decision before related work proceeds)

See docs/project/requirements.md "Open requirement questions" for
Camera360-specific product questions (frame coverage, FOV handling,
export/sharing, output format) that should be confirmed before building
features that depend on them.
