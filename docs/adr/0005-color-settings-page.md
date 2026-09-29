# 0005. Color settings page for semantic highlighting
Status: Accepted, partly unverified (was TODO item 6)

## Decision
`JsonnetColorSettingsPage` (Settings > Editor > Color Scheme > Jsonnet) lists the lexer keys and the four semantic keys. Its demo
text uses `<tag>`s to paint the semantic ranges.

## Consequences
Tests: page registered via `plugin.xml`, covers every semantic key, demo text is valid and evaluates, every tag is mapped. Never
looked at in a running IDE (`runIde`); tracked in `TODO.md`.
