# ADR-0001: Camera360 is the primary project; GoldenCare is out of scope

## Status

ACCEPTED (2026-09-10)

## Context

This repository was found to contain two unrelated apps with no shared
code: GoldenCare (React Native, elder-care app shell) and Camera360
(native Android, 360° panorama capture app) — see
docs/architecture/README.md. It was unclear which one, if either, was the
actual project the user wanted to work on.

The user then stated the project's purpose directly: use the phone's
camera to capture every angle and stitch all the captures into one
complete panorama photo. That description matches Camera360's existing
implementation exactly (`CaptureViewModel`, `GyroscopeManager`,
`StitchingEngine`) and does not match GoldenCare at all.

## Decision

**Camera360 (native Android) is the project.** GoldenCare (React Native)
is out of scope going forward.

GoldenCare's source code is left untouched in the repository (not
deleted) — it is simply no longer an active target for feature work,
architecture decisions, or task planning under this knowledge system,
unless the user explicitly reintroduces it.

## Alternatives considered

- Keep developing both apps in parallel — rejected: user explicitly said
  GoldenCare "không còn liên quan" (no longer relevant).
- Delete GoldenCare's code outright — rejected: not requested, and
  removing working code the user didn't ask to remove is unnecessary risk
  (see repo-wide guidance on reversible vs. destructive actions).

## Consequences

- docs/project/overview.md and requirements.md now describe Camera360 as
  the project's identity, not as one of two equally-weighted apps.
- rules/react-native/*, docs/architecture/goldencare-app.md, and
  docs/modules/goldencare-navigation.md remain in the repo as reference
  for legacy/out-of-scope code, but are no longer part of the default
  AI-agent reading path (rules/ai-agent/context-management.md) unless a
  task explicitly names GoldenCare.
- The open blockers about GoldenCare/Camera360's relationship
  (tasks/state/blockers.md items 1–2) are superseded by this decision —
  Camera360 does not need to become GoldenCare's Android host; it is its
  own product.
- If GoldenCare is ever revived or the repo is split, record that as a
  new ADR rather than reversing this one silently.
