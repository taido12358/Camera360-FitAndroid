# Secrets Rules

- Neither app currently has any secret, API key, or credential in source.
  Keep it that way: if a backend/API is added, load secrets from
  environment/local (git-ignored) config, never commit them.
- Android release signing keystore (once created for real releases) must
  never be committed — see rules/build-release/signing.md.
- Check `.gitignore` covers any new secret-bearing file type before
  introducing one.
