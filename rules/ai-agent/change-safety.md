# Change Safety

- Never claim a fix/feature works without following
  rules/testing/manual-qa.md (or writing/running a real unit test) — this
  repo has near-zero automated coverage today, so manual verification is
  currently load-bearing.
- Do not silently fix the mismatches documented in this knowledge system
  (unwired `AppNavigator`, undeclared `react-navigation`/`zustand` deps,
  RN/Android package-name mismatch) as a side effect of an unrelated
  task — flag them and fix only if the task calls for it, since fixing
  them may itself require a decision (e.g. is `android/` supposed to be
  GoldenCare's host, or is Camera360 intentionally standalone?).
- Do not merge the two apps' code, build files, or docs into one
  narrative — keep them documented and modified separately.
- Do not add Docker/Kubernetes/backend scaffolding "for consistency" with
  generic templates — neither app has a backend; don't invent one.
