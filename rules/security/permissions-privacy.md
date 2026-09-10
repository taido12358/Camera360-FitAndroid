# Permissions & Privacy Rules

## Camera360

- Declared permissions: `CAMERA` (runtime), `WRITE_EXTERNAL_STORAGE`
  (maxSdkVersion 28 only — correctly scoped to pre-scoped-storage
  Android versions). Do not add broader storage permissions than needed;
  `MediaStore` (API 29+) is already used correctly in `copyToGallery` and
  requires no extra permission.
- Captured frames and the final panorama are personal photo content —
  they are written to internal `filesDir` (private) and mirrored to the
  public gallery. Any future feature that uploads these off-device
  (cloud backup, sharing, sync) needs explicit user consent UI and a
  documented data-handling policy — none exists today.
- Handle `CAMERA` permission denial gracefully in the UI; never assume
  granted.

## GoldenCare

- No permissions are requested yet. It is a health/elder-care app
  (appointments, medications) — if real user health data is added later,
  treat it as sensitive: no analytics/logging of PII, and revisit this
  file with concrete rules before that data exists.
