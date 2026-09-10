# State Management (GoldenCare)

- Zustand is the established pattern (`src/store/useAuthStore.ts`). Use it
  for any new cross-screen state rather than introducing Redux, Context
  API, or another state library — that would be an ADR-worthy decision.
- One store file per domain concern (auth, etc.) — do not grow
  `useAuthStore` to hold unrelated state; add a new store file instead.
- No persistence exists yet (state resets on app restart). If a task needs
  persistence, add it explicitly (e.g. `zustand/middleware` `persist` with
  AsyncStorage) — do not assume it already persists.
- `src/store/` must not import from `src/screens/` or `src/navigation/`
  (see rules/architecture/module-boundaries.md).
