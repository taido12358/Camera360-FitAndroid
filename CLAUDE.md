# Project Index

This file is an INDEX only. It contains no explanations, no rules content, no
architecture detail, no progress history, and no task detail. Every topic
below links to the file that owns it — read that file for the real content.

## Project

Overview (what this repo actually contains):
→ docs/project/overview.md

Requirements:
→ docs/project/requirements.md

---

## Rules

Rules index:
→ rules/README.md

Architecture rules:
→ rules/architecture/README.md

React Native rules (GoldenCare app):
→ rules/react-native/README.md

Android native rules (Camera360 app):
→ rules/android-native/README.md

Testing rules:
→ rules/testing/README.md

Security rules:
→ rules/security/README.md

Build & release rules:
→ rules/build-release/README.md

DevOps rules:
→ rules/devops/README.md

AI Agent rules:
→ rules/ai-agent/README.md

---

## Architecture

Architecture overview:
→ docs/architecture/README.md

Camera360 app (native Android — the project):
→ docs/architecture/camera360-app.md

Data flow:
→ docs/architecture/data-flow.md

GoldenCare app (React Native — out of scope, reference only):
→ docs/architecture/goldencare-app.md

---

## Current State

Current state:
→ tasks/state/current-state.md

Current phase:
→ tasks/state/current-phase.md

Current blockers:
→ tasks/state/blockers.md

---

## Tasks

Task index:
→ tasks/README.md

Current task:
→ tasks/active/current-task.md

Backlog:
→ tasks/backlog/README.md

Completed tasks:
→ tasks/completed/README.md

Blocked tasks:
→ tasks/blocked/README.md

---

## Progress Logs

Log index:
→ logs/README.md

Current progress:
→ logs/progress/current.md

Latest AI session:
→ logs/ai-agent/latest.md

AI session history:
→ logs/ai-agent/README.md

---

## Architecture Decisions

ADR index:
→ docs/decisions/README.md

---

## Modules

Module documentation index:
→ docs/modules/README.md

---

## Development Workflow

Development workflow:
→ docs/workflow/development.md

Testing workflow:
→ docs/workflow/testing.md

Release workflow:
→ docs/workflow/release.md

---

## Invariants (short, immutable — do not expand this list here)

- **The project is Camera360**: capture a scene from every angle with the
  phone camera, stitch all captures into one complete panorama, on-device.
  See docs/project/overview.md and
  docs/decisions/ADR-0001-camera360-is-primary-project.md.
- GoldenCare (React Native, repo root/`src/`) is a separate, **out of
  scope** app kept for reference only — do not read or modify it unless a
  task explicitly names it.
- No backend/API/database exists yet. Do not invent one.
- CLAUDE.md stays index-only. New rules, architecture notes, task details,
  or history go into the linked files/dirs above — never appended here.
