# Android Native Coding Standard (Camera360)

- Kotlin only, Jetpack Compose for all UI — no XML layouts, no Views
  interop unless a specific library requires it.
- UI state flows through a `ViewModel` exposing `StateFlow`, as in
  `CaptureViewModel` — Composables must be pure functions of that state
  plus callbacks, not hold their own mutable business state.
- Package stays flat (`com.camera360`) with a `ui/` sub-package for
  Compose screens/theme — do not introduce a multi-module Gradle split
  without an ADR; the app is small enough that it isn't warranted yet.
- Prefer `data class` + immutable `copy()` updates for state (as
  `CaptureState`/`FrameTarget` already do) over mutable fields.
