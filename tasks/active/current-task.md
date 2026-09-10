# TASK-002

## Title

Confirm project scope: Camera360 is the project, GoldenCare out of scope

## Goal

Resolve the ambiguity from TASK-001 (two unrelated apps, unclear which was
the real project) using the user's direct statement of intent, and update
the knowledge system so future sessions don't re-litigate it.

## Scope

- Record the decision as ADR-0001.
- Update docs/project/overview.md, docs/project/requirements.md,
  docs/architecture/README.md, rules/README.md, rules/ai-agent/
  before-coding.md and context-management.md, CLAUDE.md invariants,
  tasks/state/*, logs/progress/current.md.
- Do not delete GoldenCare code or docs — mark out of scope only.

## Dependencies

TASK-001 (initial knowledge system).

## Affected App/Module

Documentation only.

## Acceptance Criteria

- CLAUDE.md and docs/project/overview.md unambiguously identify Camera360
  as the project.
- GoldenCare remains documented but clearly marked out of scope, not
  deleted.
- Open Camera360 product questions are captured in
  docs/project/requirements.md rather than left implicit.

## Testing

N/A (documentation-only change).

## Documentation

This task's output is the updated docs themselves.

## Status

IMPLEMENTED
