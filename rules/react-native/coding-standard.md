# React Native Coding Standard

- TypeScript everywhere (`tsconfig.json` is already strict-ish — do not
  weaken `strict` settings to work around a type error; fix the type).
- Functional components with hooks only — no class components (none exist
  today; keep it that way).
- Styling via `StyleSheet.create` co-located in the same file as the
  component, as done in `App.tsx` and `src/components/`. Do not introduce
  a second styling approach (styled-components, inline style objects for
  static styles) without an ADR.
- Screens live in `src/screens/`, one file per screen, PascalCase filename
  matching the exported component name.
- Shared UI goes in `src/components/`; do not duplicate a component that
  already exists there (currently `Button`, `Card`).
