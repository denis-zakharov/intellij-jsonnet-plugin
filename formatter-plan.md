> **Historical document.** This is the initial plan for the formatter work, kept as written when it was approved
> (2026-09-21). It is not maintained. What actually shipped, the gotchas found on the way and the current state are in
> `AGENTS.md` ("Formatter") and `TODO.md` (items 14–22).
>
> Where the implementation departed from this plan:
> - Phases 1–3 and 5 were done; **phase 4 (Block-model rewrite, typing-time indentation) was not** — see `TODO.md` item 15.
> - `Options` gained `rewriteTokens` (needed for a real whitespace-only mode); `WHITESPACE_ONLY` is not just "styles = leave".
> - The service maps carets by hand (`JsonnetTextEdits.mapOffset`); the platform's default left them in front of inserted whitespace.
> - `format()` converts `StackOverflowError` (10,000 nested arrays) into a `ParseError`; not anticipated here.
> - Goldens: 34 upstream + 1 error golden (go-jsonnet writes the error text into the golden) + 4 oracle-generated cases;
>   idempotence is asserted on the upstream goldens only, because jsonnetfmt is not idempotent on `(((1)))`.
> - `scripts/jsonnetfmt-conformance.py` was **not** written; the opt-in `JsonnetFormatterDifferentialTest` does its job.
> - The "whitespace-only refused if text would change" test is a unit test on the guard: our PSI lexer rejects `1_000`,
>   so such a file never reaches the service.
> - Not verified as planned: reformat-on-save and "changed lines" (only the Reformat Code action is tested), and the
>   manual `runIde` comparison against `tk fmt --stdout`.

---

# Plan: proper Jsonnet formatting (jsonnetfmt port + on-type indentation)

## Context

Today the plugin's formatter is a v1 Block model (`formatter/JsonnetBlock.kt`, `JsonnetFormattingModelBuilder.kt`, 145 lines):
indent for `{}`/`[]`/args/params plus a `SpacingBuilder`. It has no line-break policy, no comment/text-block
handling, no blank-line rules, and it registers no code-style settings, so IntelliJ's default indent of **4** applies where
`jsonnetfmt`/`tk fmt` use **2**. The earlier `jsonnetfmt` shell-out was deleted, so there is currently no way to get
`tk fmt`-identical output from the IDE.

Goal: **Reformat Code produces byte-identical output to `jsonnetfmt`/`tk fmt`** (checked: `tk fmt --stdout` == `jsonnetfmt`
with default options), while *typing* (Enter, `}`, paste) gets fast, error-tolerant, IDE-native indentation that agrees with it
on common shapes. Decisions confirmed with the user: default to jsonnetfmt's token-rewriting defaults (each with a toggle);
selection reformat = format whole file, apply only hunks in range.

## What go-jsonnet does (reference: `go-jsonnet` v0.22.0, checked out at scratchpad `go-jsonnet/`, `cpp-jsonnet` submodule too)

`internal/formatter/jsonnetfmt.go: FormatNode` = parse to a **fodder-preserving AST**, run a fixed pipeline, unparse:
`SortImports → removeInitialNewlines → EnforceMaxBlankLines → FixNewlines → FixTrailingCommas → FixParens →
(Remove|Add)PlusObject → NoRedundantSliceColon → Strip* → PrettyFieldNames → EnforceStringStyle → EnforceCommentStyle →
FixIndentation → unparse`.

- *Fodder* = whitespace + comments between tokens, stored on the following token as `LineEnd | Paragraph | Interstitial`
  elements carrying `blanks`/`indent`. That is what lets it round-trip comments.
- **No width-based wrapping.** `FixNewlines` is shallow "all-or-nothing": if any element of an object/array/args/params/comp
  is preceded by a newline, every element gets one (`FodderEnsureCleanNewline`); otherwise none does.
- `FixIndentation` (812 lines) tracks a `column` and an `indent{base, lineUp}` pair. If the first element shares the opener's line,
  later lines *line up* with its column (`local f(x,\n        y)`, `std.foo(1,\n                2)`); otherwise `base + indent`.
  Closers go to `base`. "Strong" variants (binary ops, array/args with a later newline, parens) rebase on the column.
  Text-block bodies are re-indented at `base + indent`.
- Only lexer + parser (`SnippetToRawAST`) are needed; the desugarer/static analysis are not.

Probe on a messy sample confirmed these quirks (`if a then\n    1 else 2`, `{ replicas: 3, list: [\n    1,` hanging inside a
one-line object) — an IntelliJ Block model cannot reproduce them, which is why full reformat needs a real port.

## Architecture

Two layers with different jobs:

| Layer | Used for | Source of truth |
|---|---|---|
| **A. `fmt` port** (pure Kotlin, no IntelliJ imports) | Reformat Code (whole file, selection, on-save) | go-jsonnet, byte-exact |
| **B. Block model** (existing `formatter/`, rewritten) | Enter, typed `}`/`]`/`)`, paste, quick-fix ad-hoc formatting, and any file with syntax errors | mirrors A's indent rules on common shapes |

A cannot serve typing: it needs a fully parsed file, and mid-edit files have syntax errors. B runs on our error-tolerant PSI.

### A. Port — new package `io.github.denis_zakharov.jsonnettanka.fmt` (same Gradle module, zero `com.intellij` imports)

Own lexer + parser rather than PSI-derived: our PSI is *flat* (`a.b(c) + d.e` is one `Expr`, no precedence), while the passes
need the real tree (`leftRecursive`, `Binary`, `Apply` targets), and fodder has to be exact.

| Go source (LOC) | Kotlin file |
|---|---|
| `ast/ast.go` (node types only), `ast/fodder.go` (179) | `fmt/Ast.kt`, `fmt/Fodder.kt` |
| `internal/parser/lexer.go` (1039), `string_util.go` (152) | `fmt/Lexer.kt`, `fmt/StringUtil.kt` |
| `internal/parser/parser.go` (1410) | `fmt/Parser.kt` |
| `internal/pass/pass.go` (474) base visitor | `fmt/AstPass.kt` (open class, default recursion) |
| `internal/formatter/*.go` — 10 passes, `fix_indentation.go`, `unparser.go`, `sort_imports.go` | `fmt/passes/*.kt`, `fmt/FixIndentation.kt`, `fmt/Unparser.kt`, `fmt/JsonnetFormatter.kt` (`format(text, Options): Result`) |

Port gotchas to decide up front (all silent otherwise):
- Go passes take `*Fodder` / `*bool` into nodes. Model `Fodder` as a mutable holder class and give nodes `var trailingComma`, so
  `openFodder(node)` and `fixComma(...)` translate 1:1.
- **Column math uses Go `len()` = UTF-8 bytes** (`c.column += len(node.Value)`, `len(*field.Id)`, comment text). Use UTF-8 byte
  length, not `String.length`, or non-ASCII strings/comments shift hanging-indent alignment. Same for `SortImports`' key compare
  (Go compares bytes; Kotlin `compareTo` compares UTF-16 units — differs for supplementary chars; see `import_sorting_unicode`).
- IntelliJ `Document` text is always `\n`, so CRLF handling only matters for the standalone tests.
- `Options` = Go's struct (`indent`, `maxBlankLines`, `stringStyle`, `commentStyle`, `prettyFieldNames`, `padArrays`, `padObjects`,
  `sortImports`, `useImplicitPlus`) with `Options.DEFAULT` = `DefaultOptions()` and `Options.WHITESPACE_ONLY` (all token-rewriting
  passes and trailing-comma/paren fixes off; needed for `canChangeWhiteSpaceOnly`).
- Errors: `ParseError(line, col, msg)`; message text need not match Go's.
- **License:** go-jsonnet is Apache-2.0, the repo is MIT. Keep each derived file's Apache header + "ported from go-jsonnet v0.22.0
  (567b61a), modified" note, and add go-jsonnet to `THIRD_PARTY_NOTICES.md` with the full license text.

### A′. IDE wiring for whole-file / selection formatting

- `formatter/JsonnetFormattingService : AbstractDocumentFormattingService` (public platform base class, verified in
  2025.1 sources), registered as `<formattingService>`. Features `{FORMAT_FRAGMENTS}` — **not** `AD_HOC_FORMATTING`. From
  `FormattingServiceUtil.findService`: explicit invocations (Reformat Code, reformat-on-save, "changed lines") reach us; ad-hoc ones
  (paste, quick-fix/refactoring cleanup) keep going to `CoreFormattingService` → layer B.
- `canFormat(file)`: language is Jsonnet **and** `!PsiTreeUtil.hasErrorElements(file)`. Broken files fall back to layer B
  (today's behaviour) instead of a refusal. If the port then rejects a PSI-valid file, `formatDocument` leaves the text alone and
  raises a balloon on the existing `Jsonnet` notification group with line:col.
- `formatDocument(document, ranges, ctx, canChangeWhiteSpaceOnly, quick)`: read `JsonnetCodeStyleSettings` + indent from
  `ctx.codeStyleSettings`, run `JsonnetFormatter`, then apply the result as **minimal char-diff edits**
  (`ComparisonManager.compareChars`, applied last→first) — keeps caret, folds, bookmarks and gives a tidy undo step. Whole-file
  range: apply every fragment; selection/VCS-range: apply only fragments intersecting a requested range. When
  `canChangeWhiteSpaceOnly` use `Options.WHITESPACE_ONLY`.
- Code style: `JsonnetCodeStyleSettings : CustomCodeStyleSettings` (the toggles above), `JsonnetLanguageCodeStyleSettingsProvider`
  (`<langCodeStyleSettingsProvider>`) with default `INDENT_SIZE=2`, `USE_TAB_CHARACTER=false`, a sample text, and the custom
  options laid out under a "Formatter" group; `<codeStyleSettingsProvider>` for the configurable. This also fixes the 4-vs-2
  default for typing. `.editorconfig` `indent_size` works for free.
- Format-on-save needs no code (platform Actions-on-Save routes through the same service); covered by a test.

### B. Block model (typing) — rewrite `JsonnetBlock` indent rules to follow `FixIndentation`, on common shapes

Use `Indent` + `Alignment` (align to first element's column when it is on the opener's line, else `Indent.getNormalIndent()`), and
make `getChildAttributes(newChildIndex)` right for *incomplete* constructs (`{ a: 1,⏎`, `f(a,⏎`, `local x =⏎`, `if c then⏎`, `a +⏎`).
Shapes in priority order: object/array member lists; args/params (hanging + broken-after-paren); closers at opener indent;
`local` chains (body at same indent as `local`); `then`/`else` at base; binary-operator continuation; comprehensions;
comments; text blocks.

Other typing work (each is a small, separately-testable item):
1. `JsonnetBraceMatcher`: make `(`…`)` structural (`true`) so a typed `)` re-indents like `}`/`]`; check it does nothing on single-line calls.
2. Rewrite `jsonnetSpacingBuilder` from the unparser's spacing (pad objects `{ a }`, no pad arrays, `f(x)`, `a.b`, unary ops, `:`/`::`/`+:`,
   keep line breaks and blank lines); it currently forces `before(LBRACK)`/`before(LPAREN)` spacing rules that were never exercised.
3. Enter inside a text block (`|||`): the lexer emits one multi-line token, so the Block model is not consulted — verify what the caret
   gets; if it is not "previous line's indent", add an `EnterHandlerDelegate`.
4. Verify Enter on `//`/`#` comment lines and Enter between `{|}` produce the expected result before adding anything.

Rejected: deriving B's indents by running A on the file being edited (fails on syntax errors, costs a full parse per keystroke).

## Testing

1. **Port unit tests (plain JUnit, no IDE fixture — fast).** Copy upstream goldens with their licenses:
   34 `*.fmt.golden` (`go-jsonnet/formatter/testdata` 3, `cpp-jsonnet/test_suite` 32 — includes trailing-newline, import sorting,
   text blocks, DOS line endings, comments) into `src/test/resources/fmt/`. Plus idempotence: `fmt(fmt(x)) == fmt(x)`.
2. **Differential test against the real binary** (`jsonnetfmt` v0.22.0 is installed at `~/go/bin/jsonnetfmt`): opt-in
   test/Gradle property that runs every `.jsonnet/.libsonnet` in a corpus dir through both and compares. Corpora available now:
   go-jsonnet checkout (1,112 files), a sparse `k8s-libsonnet` clone (688 files, recipe in TODO.md item 2), and the user's own Tanka
   projects. Add `scripts/jsonnetfmt-conformance.py` (modelled on `scripts/sjsonnet-conformance.py`) to rerun after go-jsonnet bumps and
   to generate any extra checked-in goldens. Expect the first run to surface the non-ASCII/byte-length and sort-order issues above.
3. **Parse-error parity**: inputs go-jsonnet rejects must be rejected (no partial output).
4. **Platform tests (`BasePlatformTestCase`, extend `JsonnetPlatformIntegrationTest`).** Update the existing
   `test reformat fixes indentation...` (its expectation changes). New: `CodeStyleManager.reformat` output == golden; selection reformat
   touches only in-range hunks; `canChangeWhiteSpaceOnly` leaves the non-whitespace token stream identical; syntax-error file falls back to B;
   caret survives reformat; `myFixture.type('\n')`/`type('}')`/paste scenarios for B.
5. **Block-model consistency harness** (the check that A and B agree): for every *canonical* (jsonnetfmt-formatted) file, strip a line's
   leading whitespace, call `CodeStyleManager.adjustLineIndent` (the exact on-type path), and compare to the canonical indent. Track the match
   rate over non-comment lines as a number in the test output; iterate B's rules until only documented shapes (deep lineUp chains, exotic
   comment placement) remain. This is the mechanism that keeps B honest — same lesson as AGENTS.md: write the failing test first.
6. `./gradlew verifyPluginStructure` after `plugin.xml` changes; `./gradlew runIde` manual pass comparing Ctrl+Alt+L to `tk fmt --stdout`.

## Phasing

1. **Port core** (lexer, parser, AST/fodder, unparser with no passes) — round-trip test: `unparse(parse(x)) == x` modulo fodder normalisation.
2. **Passes + indentation** in go-jsonnet's order; goldens green after each pass. Then the differential run.
3. **IDE service + code-style settings** (A′), update the integration test, changelog.
4. **Block model rewrite + typing items** (B) driven by the consistency harness.
5. Docs: `AGENTS.md` (formatter section, byte-length gotcha, oracle binary), plan doc status block, `TODO.md`, `CHANGELOG.md`,
   `THIRD_PARTY_NOTICES.md`.

Phases 1–3 deliver "Reformat Code == tk fmt" on their own and are shippable before phase 4.

## Critical files

- New: `src/main/kotlin/.../fmt/**`, `formatter/JsonnetFormattingService.kt`, `formatter/JsonnetCodeStyleSettings.kt`,
  `formatter/JsonnetLanguageCodeStyleSettingsProvider.kt`, `src/test/kotlin/.../fmt/**`, `src/test/resources/fmt/**`,
  `scripts/jsonnetfmt-conformance.py`.
- Modified: `formatter/JsonnetBlock.kt`, `formatter/JsonnetFormattingModelBuilder.kt`, `editor/JsonnetBraceMatcher.kt`,
  `src/main/resources/META-INF/plugin.xml` (`formattingService`, `langCodeStyleSettingsProvider`, `codeStyleSettingsProvider`),
  `platform/JsonnetPlatformIntegrationTest.kt`, `THIRD_PARTY_NOTICES.md`.
- Reuse: notification group `Jsonnet`; `scripts/sjsonnet-conformance.py` as the script template; the k8s-libsonnet sparse-clone recipe (TODO.md item 2).

## Risks / open items

- Size: ~5k Go lines to port (lexer, parser, ast, 12 formatter files); the differential test is what makes that safe.
- `tk fmt` skips `vendor/` and dotfiles by default; the IDE will format them if the user asks. Consider not touching `vendor/` for reformat-on-save
  (follow-up, not decided here).
- Reformat runs synchronously in a write action; measure on the largest k8s-libsonnet file before deciding whether a size guard is needed.
- Range reformat via whole-file diff can pull in cascading indentation changes just inside the range edge; accepted.
