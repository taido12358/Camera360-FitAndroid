# GoldenCare App Architecture (React Native)

> **Out of scope.** Per
> docs/decisions/ADR-0001-camera360-is-primary-project.md, this app is not
> an active development target. Kept for reference only — do not read
> this by default (see rules/ai-agent/context-management.md).

Source of truth for how the GoldenCare RN app is structured.

## Entry point

- `index.js` → registers `App.tsx` as the RN root component.
- `App.tsx` currently renders a static placeholder screen, not
  `src/navigation/AppNavigator.tsx`. **Note:** the navigator exists in
  `src/` but is not wired up from `App.tsx` yet — confirm this before
  building on top of the navigation shell.

## Structure

```
src/
├── components/       Reusable UI components (Button, Card)
├── navigation/        AppNavigator.tsx — stack + bottom-tab layout
├── screens/           HomeScreen, LoginScreen, AppointmentScreen,
│                      MedicationScreen, ProfileScreen
├── store/             useAuthStore.ts (zustand) — local, in-memory auth
│                      state only, no persistence, no real backend call
└── theme/             colors.ts
```

## Navigation shape (as coded, not necessarily wired to App.tsx)

- Root stack switches between `Auth` and `Main` based on
  `useAuthStore().isAuthenticated`.
- `Auth` stack: `Login` only.
- `Main` bottom-tab: `Home`, `Appointment`, `Medication`, `Profile`.

## State management

- Zustand (`src/store/useAuthStore.ts`) is the only state store present.
- No persistence layer (no AsyncStorage/MMKV), no network client, no API
  layer of any kind exists in this app yet.

## Dependencies of note

- `react-navigation` (native-stack + bottom-tabs) — present in code but not
  yet confirmed installed in `package.json` (verify before relying on it —
  `package.json` currently only lists `react` and `react-native` as runtime
  deps).

## Rules

See rules/react-native/README.md for coding, state-management, and
navigation conventions to follow when extending this app.
