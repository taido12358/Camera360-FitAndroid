# Dependency Rules

## GoldenCare (React Native)

- Before using a package in code (e.g. `react-navigation`, `zustand`),
  confirm it is actually declared in `package.json`. This repo currently
  has code (`AppNavigator.tsx`, `useAuthStore.ts`) that imports packages
  not present in `package.json` dependencies — treat that as a bug to fix
  (add the dependency), not a pattern to repeat.
- Keep `dependencies` limited to what ships in the app; dev-only tooling
  belongs in `devDependencies`.

## Camera360 (native Android)

- Keep third-party dependencies in `android/app/build.gradle` minimal —
  the app currently has zero image-processing/CV libraries (stitching is
  hand-written). Adding one (e.g. OpenCV) is an architecture decision —
  write an ADR first.
