# Module: GoldenCare Navigation & Auth Shell

Files: `src/navigation/AppNavigator.tsx`, `src/store/useAuthStore.ts`.

## Public surface

- `AppNavigator` (default export) — root navigator, switches `Auth` vs.
  `Main` based on `useAuthStore(state => state.isAuthenticated)`.
- `useAuthStore` (zustand) — `user`, `isAuthenticated`, `login(user)`,
  `logout()`. No persistence, no selectors beyond direct field access.

## Gotchas

- `App.tsx` does not currently render `AppNavigator` — see
  docs/architecture/goldencare-app.md. Confirm this before assuming the
  navigation shell is reachable at runtime.
- `react-navigation` (native-stack, bottom-tabs) is used in
  `AppNavigator.tsx` but not listed in `package.json` dependencies —
  verify it installs before running the app after wiring in the navigator.
- Tab icons are all `() => null` — no icon library wired in yet.
