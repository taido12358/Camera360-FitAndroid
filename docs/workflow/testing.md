# Testing Workflow

See rules/testing/README.md for what must be tested and how. Summary of
what exists today:

## Camera360 (native Android)

- No test source set currently exists (`android/app/src/test` /
  `androidTest` not present). `StitchingEngine` is pure Kotlin and is the
  best first candidate for unit tests (see rules/testing/unit.md).
- Manual QA is currently the only verification method — see
  rules/testing/manual-qa.md before merging capture/stitching changes.

## GoldenCare (React Native)

- `package.json` has a `jest` preset (`react-native`) wired via
  `npm test`, but no test files exist yet.

## Before marking a task TESTED or VERIFIED

Follow the status definitions in rules/ai-agent/after-coding.md — do not
use "done" language until the relevant tests above actually exist and
pass.
