# Module Boundaries

- GoldenCare (`src/`, `App.tsx`, `index.js`) and Camera360
  (`android/app/src/main/java/com/camera360/`) are **separate apps**. Do
  not import code across them, do not add a dependency from one build to
  the other, and do not describe them as parts of one system in docs or
  code comments.
- Any change that would connect the two (e.g. embedding Camera360 as an RN
  native module for GoldenCare) is an architecture decision — write an ADR
  first (docs/decisions/README.md) before implementing it.
- Within Camera360: `StitchingEngine` must stay free of Android UI/Context
  dependencies (it currently only touches `android.graphics` for bitmap
  decode/encode) so it stays unit-testable. Do not add `ViewModel`,
  `Context`, or Compose imports to it.
- Within GoldenCare: `src/store/` must not import from `src/screens/` or
  `src/navigation/` (state should not depend on presentation).
