# 0007. Marketplace readiness, MIT licence, no Java dependency
Status: Accepted (was TODO item 9)

## Decision
- Ship `README.md` (with a *Limitations* section written against the code), `CHANGELOG.md`, a full `plugin.xml` description and
  change-notes, `pluginIcon.svg`, and `THIRD_PARTY_NOTICES.md` (licences read from the shipped artifacts' POMs, bundled under
  `META-INF/`). `buildPlugin` produces a valid ~14 MB zip.
- **Own licence: MIT.** `THIRD_PARTY_NOTICES.md` reproduces each bundled component's full licence text and copyright line
  (names and links aren't enough for MIT/BSD/Apache in a binary distribution). xz 1.11 is 0BSD, not Public Domain. Two upstream gaps
  are recorded there: scalatags ships no licence file (POM says MIT), lz4-java's native libs embed LZ4/xxHash (BSD-2).
- **Removed `<depends>com.intellij.java</depends>`** (a Phase 0 leftover) and the matching `bundledPlugin`: nothing uses Java PSI and
  it made the plugin uninstallable in GoLand, where most Tanka users presumably are. Don't add it back.
- Signing/publishing is wired (`intellijPlatform { signing; publishing }`, `.github/workflows/release.yml`); the human steps are in
  `docs/publishing.md`.

## Consequences
Regenerate `THIRD_PARTY_NOTICES.md` whenever the sjsonnet version changes and recheck each licence upstream. Steps needing a person
(screenshots, `verifyPlugin` across IDEs, credentials) are in `TODO.md`.
