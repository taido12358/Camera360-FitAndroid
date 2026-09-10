# Git Rules

- Single `master` branch exists today, tracked to `origin/master`.
- Commit messages should state which app a change touches (GoldenCare or
  Camera360) since the repo holds both — do not write ambiguous messages
  like "fix bug" without naming the app/module.
- Because the two apps are unrelated (see
  rules/architecture/module-boundaries.md), prefer commits that touch only
  one app at a time where practical, to keep history reviewable.
