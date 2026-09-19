# Architecture Decision Records (ADR) Index

Source of truth for architecture decisions, listed as `ADR-NNNN-title.md`.

## When to write an ADR

Any decision that is hard to reverse or affects both apps (or splits them),
for example:
- Whether to split this repo into two repos (GoldenCare vs. Camera360).
- Whether/how to wire `App.tsx` to `AppNavigator`.
- Adding a backend/API for either app.
- Choice of state-management, navigation, or persistence library for
  GoldenCare.
- Changing the stitching algorithm or camera pose model in Camera360.

## Template

→ template.md

## Existing decisions

- ADR-0001 — Camera360 is the primary project; GoldenCare is out of scope
  → ADR-0001-camera360-is-primary-project.md
- ADR-0002 — Support phones without a rotation-vector sensor via gravity + image
  registration (manual mode)
  → ADR-0002-manual-mode-gravity-plus-image-registration.md
