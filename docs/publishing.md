# Publishing to JetBrains Marketplace

`.github/workflows/ci.yml` checks every push and PR; `.github/workflows/release.yml` signs and
publishes when a `vX.Y.Z` tag is pushed. This file is the one-time setup and the per-release routine.

## One-time setup

1. **GitHub repository.** Create it and push (`git remote add origin …`). Then add a `<vendor url="…">`
   to `plugin.xml` and enable Actions.
2. **Let CI run once**, and check both jobs are green — especially `plugin-verifier`, which has never
   run (README says GoLand/IU compatibility is plausible, not verified). If it runs out of disk or time,
   tune it in `ci.yml`. It doesn't cache the downloaded IDEs yet; add that once you know where they land.
3. **Create the plugin on the Marketplace — manually, once.** The API can update a plugin but not create
   one. <https://plugins.jetbrains.com/plugin/add> → upload `./gradlew buildPlugin`'s zip
   (`build/distributions/jsonnet-tanka-0.1.0.zip`), pick a vendor/organization, license (MIT), source
   URL, and screenshots (Preview, Imports, Color Scheme page — needs a running IDE). The plugin id
   `io.github.denis-zakharov.jsonnet-tanka` is permanent. JetBrains reviews the first upload (and
   updates) before it goes public.
4. **Marketplace token.** <https://plugins.jetbrains.com/author/me/tokens>, from the account that owns
   the plugin.
5. **Signing material** (optional for the Marketplace, but this workflow signs). Keep the files out of
   the repo (`.gitignore` covers `*.pem` and `chain.crt`):
   ```sh
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 365 -out chain.crt
   ```
   Follow JetBrains' "Plugin Signing" page if these steps have changed. To rotate, run this again
   (the certificate above expires after a year).
6. **GitHub environment.** Settings → Environments → new `marketplace`; add *required reviewers* so a
   release needs your click, and these secrets: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN` (contents of
   `chain.crt`), `PRIVATE_KEY` (contents of `private.pem`), `PRIVATE_KEY_PASSWORD`.

## Releasing a version

1. Bump `pluginVersion` in `gradle.properties`.
2. Add a `## [X.Y.Z] - date` section to `CHANGELOG.md` (the workflow refuses to run without it) and
   update `<change-notes>` in `plugin.xml`, which is hand-written and not generated from the changelog.
3. Commit, then `git tag vX.Y.Z && git push origin main vX.Y.Z`. The tag must equal `v` + `pluginVersion`.
4. Approve the run in the `marketplace` environment. It re-runs CI's checks, signs, uploads, and
   creates a GitHub release with the zip.
5. The Marketplace reviews the upload before it is visible.

A version with a suffix (`0.2.0-beta.1`) is published to the `beta` channel and marked a pre-release
on GitHub; a plain one goes to `default`.

**0.1.0 specifically:** it is uploaded by hand in step 3 above, so don't tag `v0.1.0` (the API would reject
the duplicate version and the run would fail). The first tagged release is the next version.
