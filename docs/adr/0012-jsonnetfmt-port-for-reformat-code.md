# 0012. Reformat Code is a byte-exact port of `jsonnetfmt`
Status: Accepted (were TODO items 14, 16–21)

## Context
The v1 Block-model formatter had no line-break policy, no comment/text-block handling, and IntelliJ's default indent of 4 applied where
`jsonnetfmt`/`tk fmt` use 2. Goal: Reformat Code output equals `jsonnetfmt`/`tk fmt` (verified: `tk fmt --stdout` equals `jsonnetfmt`
for default options). Original plan: [`../formatter-plan.md`](../formatter-plan.md).

## Decision
- `fmt/` is a pure-Kotlin (no `com.intellij`) port of go-jsonnet **v0.22.0**'s lexer, parser, fodder AST, passes, `FixIndentation` and
  unparser, behind `JsonnetFormattingService` (`order="first"`, `FORMAT_FRAGMENTS` only, so paste/quick-fix formatting stays on the Block
  model). Our PSI is flat (no operator precedence), hence the port has its own parser. Apache-2.0 headers stay on every ported file.
  Code-style page with the jsonnetfmt toggles, default indent 2.
- **Oracle-driven.** The real binary is the spec: `scripts/jsonnetfmt-conformance.py` (`version`, `goldens [--write]`, `fetch`,
  `differential`), ported release pinned in `fmt/PortedFrom.kt`, opt-in `JsonnetFormatterDifferentialTest` (10 option variants; 3,478
  files x 10 variants, 0 mismatches at the time), goldens (34 upstream + 1 error + 4 oracle-generated), and weekly opt-in CI
  (`.github/workflows/extra-ci.yml (`differential` job)`, also a release-checklist step).
- **go-jsonnet's quirks are the spec; don't fix them** (list in `AGENTS.md` "Formatter"). jsonnetfmt isn't idempotent on `(((1)))`.
- Columns are UTF-8 byte counts; import sorting compares code points.
- `WHITESPACE_ONLY` (`rewriteTokens = false`) is a property test since it has no oracle: same non-whitespace characters. It found three
  canonicalizations no option turns off (digit separators, `a[1::]`, `|||-`); the service's `sameApartFromWhitespace` guard refuses them.
- **Digit separators** (`1_000`) added to `Jsonnet.flex`; the port drops them, so a whitespace-only reformat of `{a:1_000}` is refused.
- **`vendor/` and dot-files are skipped** like `tk fmt` (`JsonnetFormatExclusions`, setting `SKIP_VENDOR_AND_DOTFILES`). Handled inside the
  service, not `canFormat`, because declining would hand the file to the Block model, which would reformat it.
- Entry points tested (`JsonnetFormattingServiceTest`): several ranges, directory reformat, Reformat File + Optimize Imports (sorting is
  part of the formatter), the on-save processor path, and the "Can't format" notification. Checked by hand in `runIde` on a real Tanka
  environment and k8s-libsonnet (undo in one step, caret/folds survive, code-style page, `.editorconfig`).
- **Performance/limits:** largest real file (123 KB) formats in 4–17 ms; synthetic 20 MB in ~1.9 s + 2.3 s diff. No size guard, no
  off-EDT step. `JsonnetTextEdits` catches `DiffTooBigException`. The JVM stack overflows at ~200 nested `{a:`, so `format()` retries on a
  worker thread with a 512 MB stack (200,000 levels) before failing with "nested too deeply".

## Consequences
Rerun the differential after touching `fmt/` or bumping go-jsonnet. Carets are mapped by hand (`JsonnetTextEdits.mapOffset`).
Testing gotcha: set code style before `configureByText`. Typing-side follow-up: [0013](0013-block-model-typing-indentation.md).
