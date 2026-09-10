# Navigation Rules (GoldenCare)

- `src/navigation/AppNavigator.tsx` is the single navigation root. Do not
  create a second navigator elsewhere.
- Before adding a screen/tab, add its route to the relevant
  `ParamList` type (`RootStackParamList` / `AuthStackParamList` /
  `MainTabParamList`) — keep navigation fully typed.
- `App.tsx` currently does **not** render `AppNavigator` (see
  docs/architecture/goldencare-app.md). If your task is to make the app
  navigable, wiring this up is the first step — confirm with the task
  description before assuming it's already done.
- Before adding `react-navigation` screens/dependencies, confirm the
  packages are declared in `package.json` (see
  rules/architecture/dependency-rules.md) — they currently are used in
  code but not declared.
