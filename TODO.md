# TODO

Outstanding work only. Completed items and closed questions are recorded as decision records in
[`docs/adr/`](docs/adr/README.md); the original phased plan is in [`docs/initial-plan.md`](docs/initial-plan.md) (historical).
Features deliberately not planned are in [ADR 0015](docs/adr/0015-rejected-scope.md).

## Needs a running IDE (`./gradlew runIde`)

- **Look at what was never seen:** the Preview tool window, the Imports tool window ([0006](docs/adr/0006-import-graph-tool-window.md))
  and the Color Scheme page ([0005](docs/adr/0005-color-settings-page.md)).
- **Screenshots** of those three for the Marketplace listing ([0007](docs/adr/0007-marketplace-readiness.md)).

## Publish to JetBrains Marketplace

Steps and rationale: [`docs/publishing.md`](docs/publishing.md). In order:

1. [ ] Create the GitHub repository and push; add `<vendor url="…">` to `plugin.xml`; enable Actions.
2. [ ] Let CI run once and get both jobs green, especially `plugin-verifier` (never run; may need disk/time tuning, IDE download caching).
3. [ ] `./gradlew verifyPlugin` across the recommended IDEs (GoLand, IU, …). README says compatibility is plausible, not verified.
4. [ ] Screenshots (Preview, Imports, Color Scheme page), which needs the `runIde` check above.
5. [ ] Create the plugin on the Marketplace by hand, once: upload `./gradlew buildPlugin`'s zip (`0.1.0`), choose vendor/organization, MIT
   licence, source URL, screenshots. The plugin id `io.github.denis-zakharov.jsonnet-tanka` is permanent. The API can update but not create.
6. [ ] Create a Marketplace token (plugins.jetbrains.com/author/me/tokens) from the account owning the plugin.
7. [ ] Generate signing material (`private.pem`, `chain.crt`; kept out of the repo). The certificate expires after a year, so rotate it.
8. [ ] Create the GitHub environment `marketplace` with required reviewers and secrets `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
   `PRIVATE_KEY_PASSWORD`.
9. [ ] Wait for JetBrains' review of the first upload before it is public.
10. [ ] First tagged release is the next version, not `v0.1.0` (already uploaded by hand): bump `pluginVersion`, add a `CHANGELOG.md` section
    and `<change-notes>`, tag `vX.Y.Z`, approve the `marketplace` run. If `fmt/` changed, run the jsonnetfmt differential first.

## Small follow-ups

- **`SourceLocator.locate` runs on the EDT** on Ctrl/Cmd+click in Preview (it re-evaluates lazily). Move it off the EDT if it is ever felt
  ([0004](docs/adr/0004-preview-tool-window.md)).
- **Differential test for `manifestYamlFromJson`** against `tk`: it is a hand-written emitter and only common cases were compared (long lines,
  unusual Unicode/escapes untested) ([0008](docs/adr/0008-tanka-natives-and-sjsonnet-conformance.md)).
- **`*` continuation inside `/* */`** on Enter ([0014](docs/adr/0014-typing-niceties.md)).
- **`std.native(x=...)`** named-argument call isn't handled, because `native` is appended after the extras merge
  ([0008](docs/adr/0008-tanka-natives-and-sjsonnet-conformance.md)).
