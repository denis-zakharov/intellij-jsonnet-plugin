# Releasing the plugin

`.github/workflows/ci.yml` checks every push and PR (`extra-ci.yml` holds the opt-in heavy checks: plugin verifier and the
jsonnetfmt differential); `.github/workflows/release.yml` is run by hand (Actions → Release → Run workflow), re-runs
CI and creates the `vX.Y.Z` tag and a **GitHub release** with the plugin zip. Users install it via *Settings → Plugins → ⚙ → Install Plugin
from Disk*.

**JetBrains Marketplace publishing is behind a feature flag, off by default.** Set the repository variable
`PUBLISH_TO_MARKETPLACE=true` (Settings → Secrets and variables → Actions → Variables) and the release run gains a `marketplace`
job that signs and uploads after the GitHub release exists. Without the variable that job is skipped, and none of the
Marketplace setup below is needed. Do "Releasing a version" for GitHub-only releases; do "One-time setup" only when you
turn the flag on.

**Tip build.** `.github/workflows/tip.yml` keeps a rolling `tip` pre-release (GitHub → Releases) with the latest `main` that
passed CI, as `0.1.0-dev.<shortsha>`. It rebuilds after every successful CI run on `main` and can be run by hand; it is never
published to the Marketplace and needs no setup.

## One-time setup

1. **GitHub repository.** Create it and push (`git remote add origin …`). Then add a `<vendor url="…">`
   to `plugin.xml` and enable Actions.
2. **Let CI run once**, then run the **Extra CI** workflow from the Actions tab and check its `plugin-verifier` job is
   green — it has never run (README says GoLand/IU compatibility is plausible, not verified). If it runs out of disk or
   time, tune it in `extra-ci.yml`. It doesn't cache the downloaded IDEs yet; add that once you know where they land.
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

0. If `fmt/` changed since the last release, run the **Extra CI** workflow, `differential` job (Actions tab; or locally
   `scripts/jsonnetfmt-conformance.py differential --corpus … --variants all`) and check it is green.
1. Bump `pluginVersion` in `gradle.properties`.
2. Add a `## [X.Y.Z] - date` section to `CHANGELOG.md` (the workflow refuses to run without it) and
   update `<change-notes>` in `plugin.xml`, which is hand-written and not generated from the changelog.
3. Commit and push to `main`, wait for CI to be green.
4. Actions → **Release** → Run workflow on `main`. Tick **dry_run** first if you want to see the checks pass without creating
   anything. The run checks that it is on `main`, that `vX.Y.Z` doesn't exist yet, that `CHANGELOG.md` has the section, and that
   `ci.yml` is green; only then it creates the tag (at the commit it checked) and the GitHub release with the zip. A failed run
   creates no tag: fix and run again. With the flag on, approve the run in the `marketplace` environment as well; it then signs
   and uploads.
5. (Flag on only) The Marketplace reviews the upload before it is visible.

Fallback: `git tag vX.Y.Z && git push origin vX.Y.Z` runs the same checks, but a failure leaves the tag behind (delete it with
`git push origin :refs/tags/vX.Y.Z`).

A version with a suffix (`0.2.0-beta.1`) is published to the `beta` channel and marked a pre-release
on GitHub; a plain one goes to `default`.

**0.1.0 and the Marketplace:** only if you upload 0.1.0 to the Marketplace by hand (one-time setup step 3) must you not
release `v0.1.0` with the flag on (the API would reject the duplicate version). With the flag off, 0.1.0 can be the first release.
