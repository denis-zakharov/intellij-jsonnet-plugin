# TODO

Ranked backlog for future dedicated sessions. Each item names the concrete
gap, why it ranks where it does, and pointers into the code/plan doc so a
fresh session doesn't have to re-derive context. Full narrative detail for
everything here lives in `jsonnet-tanka-intellij-plugin-plan.md`'s per-phase
status blocks and `AGENTS.md`'s gotchas — this file is the prioritized index
into those, not a replacement for them.

Ranking logic: a **trust gap** (something that might be silently wrong and
nobody would know) outranks a **missing feature** (something that's honestly
absent), which outranks **polish**. Within a tier, lower effort/risk and
higher user-facing impact rank first.

---

## P0 — Trust gaps (verify or fix before relying on the plugin at scale)

### 1. ~~Get `BasePlatformTestCase` working in this environment~~ — DONE
Root-caused and fixed. The "hang" was never the fixture — it was a real bug
(`LibsonnetFileType.getName()` mismatched its `plugin.xml` declaration,
corrupting `StubIndexImpl` init and wedging `tearDown()`'s leak-check on a
future that never completed). Fixed, and `JsonnetPlatformIntegrationTest.kt`
now gives real `BasePlatformTestCase` coverage for everything this item
listed: rename (`setName`/`handleElementRename`), the formatter's actual
`CodeStyleManager.reformat()` invocation, the stub-index build/query
pipeline, and `JsonnetUnusedDeclarationInspection`'s quick-fix wiring.
Writing those tests caught **four more real, previously-invisible bugs**,
all now fixed (full detail in AGENTS.md's Testing section):
- `JsonnetResolver.resolveLocalName` never resolved a function-sugar bind's
  own parameters inside its body (`local f(x) = x + 1;`) — broken
  go-to-definition/rename for the single most common Jsonnet idiom, since
  Phase 1.
- `JsonnetStubIndexUtil.isTopLevelExpr` indexed locals nested inside
  function-sugar bodies as if top-level (missing a `paramList == null`
  check), contradicting its own documented intent.
- `JsonnetBlock`'s formatter double-indented every object/array member and
  separately mis-indented the opening brace, together producing badly wrong
  `Reformat Code` output on any nested structure.
- `JsonnetPsiListEditUtil.deleteListMember` left stray whitespace behind
  (and could throw `PsiInvalidElementAccessException`) when removing one
  bind from a multi-bind `local`.
- **Payoff realized:** Phase 2 (VFS importer), Phase 4 (rename, formatter,
  stub index), and Phase 5 (inspection quick fixes) all now have real
  end-to-end coverage, not just review. Items 2 and 3 below remain open.

### 2. ~~Validate the stub index against a real large Tanka/vendor tree~~ — DONE
Validated against a real, large corpus: a sparse shallow clone of
`jsonnet-libs/k8s-libsonnet`'s `1.34/` directory (688 real `.libsonnet`
files, ~7MB, deeply nested, heavy use of `::` hidden fields with
string-literal names, function-sugar fields, computed values — genuinely
representative of what a real Tanka `vendor/` tree looks like), copied into
a `BasePlatformTestCase` project alongside a non-vendor project file, via a
temporary investigation test (removed afterward — it pointed at an external
fixture deliberately not checked into this repo; rerun this exact recipe if
the numbers ever need re-checking, see the AGENTS.md write-up for the
one-liner clone command).
- **Performance claim confirmed, not just plausible:** copying all 688 files
  into the project: ~1.4–1.8s. Stub-index build/query: ~9–19ms. A full extra
  PSI-error-checking parse pass over all 688 files: ~770–800ms. Heap
  delta: ~150–290MB. **Zero** vendor-path entries leaked into
  `JsonnetBindIndex`/`JsonnetFieldIndex` (the `vendor/` exclusion holds at
  scale), and a non-vendor top-level symbol was found correctly via the
  index amid the large tree. The plan's original performance worry was
  real to check but turns out unfounded — this is fast.
- **Real payoff wasn't the performance number, though — it was a genuine
  parser bug the corpus surfaced.** Every one of the 688 real files parsed
  cleanly except one: `_custom/mapContainers.libsonnet` failed on `local
  cronPatch = patch { mapContainers(f):: {...} }`. Root cause: the grammar's
  `postfixSuffix` never supported real Jsonnet's `expr { ... }` "object
  mixin juxtaposition" sugar (`expr { ... }` ≡ `expr + { ... }`) — an
  idiomatic, extremely common construct in Tanka/k8s-libsonnet code
  (`deployment.new() { spec+: {...} }`-shaped patterns) that was simply
  missing from `Jsonnet.bnf` entirely. Fixed by adding `objectLiteral` as a
  `postfixSuffix` alternative; re-ran against the same corpus afterward —
  0/688 parse errors. Three permanent regression tests added to
  `JsonnetParsingTest.kt`. **Lesson for item 2-shaped validation work
  generally: running the parser against real, large, external code finds
  the bugs no amount of hand-written fixture text will — worth doing again
  whenever the grammar changes substantially.**

### 3. ~~Wire sjsonnet's own static analysis for unresolved-reference diagnostics~~ — DONE
Spiked via `javap` on `sjsonnet_3-0.7.4.jar` and wired up. `Interpreter`
already exposes `resolver()`, `evaluator()`, `createOptimizer(...)`,
`internedStrings()`, `internedStaticFieldSets()` as public accessors — no
manual `EvalScope`/`std` assembly needed, just build an `Interpreter` the
same way `JsonnetEngine` already does. New standalone, independently unit
tested `SjsonnetStaticCheck.firstUnresolvedVariable(fileName, source)`
(`engine/SjsonnetStaticCheck.kt`) runs the real sjsonnet name-resolution
pass and returns the first unresolved name's source offset + message, or
`null`.
- **Correction to the original plan, found by testing the actual behavior
  and not just the `javap` signatures:** unresolved-variable detection
  happens in `CachedResolver.parse()` (returned as `Left(a sjsonnet.Error)`),
  not in a separate later `StaticOptimizer.optimize()` call — the
  `"Unknown variable: ..."` string found via `javap -v`'s constant-pool grep
  does live in `StaticOptimizer.class`, but that's where the message *text*
  lives, not where it actually *fires*; calling `optimize()` on a tree with
  a genuinely-undefined name doesn't throw. Two of seven tests failed on the
  first real run before this was caught. `parse()`'s `Left` also carries
  genuine `ParseError`s (real syntax errors) through the same channel —
  filtered out by type so this never double-reports what the PSI parser
  already flags.
- **`JsonnetUnresolvedReferenceAnnotator` now cross-checks every identifier
  against `SjsonnetStaticCheck`** (cached per file via `CachedValuesManager`
  — the check reparses the whole file, too expensive per-identifier) in
  addition to its existing `JsonnetResolver` PSI walk, and flags anything
  sjsonnet's real analysis catches that the hand-rolled walk misses. This is
  additive, not a replacement: `StaticOptimizer`/`CachedResolver.parse()`'s
  unresolved-name check is fail-fast (`StaticError.fail` is a Scala
  `Nothing`-returning throw), so a single pass can only ever report the
  *first* divergence in a file — it can't stand in as the sole, complete
  source of "every unresolved identifier here" the way the per-element PSI
  walk can, but it closes real trust gaps the hand-rolled walk might have.
  4 new `BasePlatformTestCase` tests lock in the end-to-end annotator
  behavior (including a regression guard for the `local f(x) = x + 1;`
  resolver bug fixed in item 1, now double-covered at both the unit and
  annotator-integration level).

---

## P1 — Real, scoped feature gaps

### 4. ~~Preview tool window: TLA vars, YAML toggle, output→source click-jump~~ — DONE
All three landed in `JsonnetPreviewPanel`, each with tests (engine-level unit
tests plus a `BasePlatformTestCase` that drives the real panel).
- **TLA vars:** second box next to ext vars; `name=string` / `name:=code`
  syntax (`PreviewVars`). This also fixed a latent bug in the ext-var box:
  `sjsonnet`'s Map-based `Interpreter` constructor treats *every* value as
  `ext-code`, so `env=prod` used to fail as an unresolved variable `prod`.
  `JsonnetEngine.VarValue(text, isCode)` now string-quotes non-code values.
- **YAML toggle:** toolbar toggle; `sjsonnet`'s own `YamlRenderer` with
  `quoteKeys = false` (bare keys, like `tk show`; string *values* stay quoted).
- **Click-jump:** Ctrl/Cmd+click an output line → `PreviewOutputPaths` maps the
  line to a value path (own JSON tokenizer; YAML via the shaded snakeyaml node
  tree's line marks) → `SourceLocator` re-evaluates lazily and walks
  `Val.Obj`/`Val.Arr` along the path → `Val.pos` → open file at offset,
  including files reached through imports. **Deliberately the position of the
  expression that *produced* the value, not the field key:** sjsonnet 0.7.4
  folds constant objects into `ConstMember(value)` with no key position
  (probed by reflection), so key positions aren't available consistently.
  Jumping into an imported file doesn't retarget Preview at it (`jumpTarget`
  guard, mutation-checked). Not built: the plan's full §4.4 "every PSI
  `TextRange` → `Expr`" mapping — click-jump didn't need it.
- **Evaluation runs off the EDT** (pooled thread; one in flight, refreshes requested meanwhile
  collapse into one re-run and the stale result is dropped; old output stays until the new is
  ready). Not `ReadAction.nonBlocking` on purpose: sjsonnet never checks for cancellation, so a read
  action held across the evaluation would block the next keystroke's write action;
  `VirtualFileText.read` takes short read actions per file instead. A running evaluation can't be
  killed, only ignored. Still on the EDT: `SourceLocator.locate` on Ctrl/Cmd+click (re-evaluates lazily).

### 5. ~~Preview/import resolution should honor unsaved buffers~~ — DONE
`VirtualFileText.read` (live `Document` if one is loaded, else VFS) is now the
single read path for the root file, imports, and error-position rendering.
Preview also listens to *every* Jsonnet document (not just the focused one) so
editing an imported file refreshes it. Covered by `JsonnetPlatformIntegrationTest`
(edit imported file → output changes; click-jump offsets refer to buffer text).
Only files with a loaded `Document` are affected (`getCachedDocument`), which
is exactly the set that can have unsaved edits.

### 6. ~~`ColorSettingsPage` for the Phase 5 semantic-highlighting keys~~ — DONE
`JsonnetColorSettingsPage` (Settings > Editor > Color Scheme > Jsonnet) lists
the lexer keys and the four semantic keys, with a demo text whose `<tag>`s paint
the semantic ranges. Tests: page registered via `plugin.xml`, covers every
semantic key, demo text is valid, evaluates, and every tag is mapped.
**Not visually verified** (no `runIde` here) — worth one look in a real IDE.

### 10. ~~Close the sjsonnet-vs-go-jsonnet gaps, incl. Tanka's native functions~~ — DONE
Measured (not assumed) against go-jsonnet v0.22.0's `testdata/` and Tanka v0.39:
sjsonnet is close to conformant (480/590 identical, 228 fail in both, **none**
where go succeeds and sjsonnet fails). Full write-up: `docs/sjsonnet-gaps.md`;
re-run with `scripts/sjsonnet-conformance.py` after any sjsonnet/go-jsonnet bump.
- **The hook exists:** `StdLibModule(nativeFunctions, additionalStdFunctions)`,
  passed as `Interpreter`'s `std` argument. The earlier "no registration hook"
  verdict only looked at `Interpreter`/`Settings` and was wrong.
- **Delivered** (`engine/extension/`, used by `JsonnetEngine`, `SjsonnetStaticCheck`,
  `StdLibRegistry`): `std.id`; go's parameter names for 14 std functions
  (named-arg calls); Tanka's 8 pure natives (`parseJson`, `parseYaml`,
  `manifestJsonFromJson`, `manifestYamlFromJson`, `escapeStringRegex`,
  `regexMatch`, `regexSubst`, `sha256`) with outputs pinned to real `tk eval`.
- **Bugs the ground-truth comparison caught:** `yes`/`on` parsed as booleans
  (SnakeYAML is YAML 1.1, yaml.v3 isn't), `null` never matching ujson's `Null`
  object, and a wrong `%g` threshold.
- **Not verified:** `manifestYamlFromJson` matches yaml.v3 on every case tried,
  but it is a hand-written emitter; exotic strings (long lines, unusual
  Unicode/escapes) haven't been compared against `tk`. A generated differential
  test would settle it.
- **Still per-feature, not covered here:** `std.native(x=...)` (`native` is
  appended after the extras merge), `helmTemplate`/`kustomizeBuild` (external
  binaries; `std.native` is `null` for them in the preview).

### 11. Number-to-string fidelity with go-jsonnet/Tanka
`std.toString(0.1)` and `"" + 0.1` are `"0.10000000000000001"` in go-jsonnet
*and Tanka* (17 significant digits), `"0.1"` in sjsonnet — a real divergence for
anything Jsonnet stringifies (ConfigMap data, labels built by concatenation).
Top-level numbers are unaffected (`tk` re-serializes them and prints `0.1`).
Also huge integers: go prints them exactly, sjsonnet rounds. **Can't be fixed
through the std hook** — concatenation happens in the evaluator, so overriding
`std.toString` alone would make the two paths disagree (details in
`docs/sjsonnet-gaps.md`). Options: upstream/patch sjsonnet, or accept and keep
it documented. Decide before promising "matches `tk`" anywhere.

---

## P2 — Stretch items deferred in the plan doc

### 7. ~~Lightweight import-graph view (not the full `DiagramProvider`)~~ — DONE
"Jsonnet Imports" tool window (`imports/`): a tree of what the focused file
imports transitively, or — with the *Imported By* toggle — what imports it.
Model (`JsonnetImportGraph`) is separate from the shell (`JsonnetImportGraphPanel`)
and tested directly (12 tests: relative/jpath resolution, `importstr` kinds,
unresolved/`tk`/directory imports, cycles, shared deps, vendor boundary, both
directions, panel wiring).
- **Resolution is shared with evaluation:** the rule moved out of
  `VirtualFileImporter` into `TankaJpath.resolveImport`, so the view can't
  disagree with what `import` really loads.
- Tree over a graph, kept finite by: cycle → `CYCLE` leaf, already-expanded
  file → `SEEN_ABOVE` leaf. `vendor/` is shown but not expanded (and vendored
  importers omitted) unless *Expand vendor/* is on — k8s-libsonnet alone is
  hundreds of files.
- Builds off the EDT (`ReadAction.nonBlocking`, cancelled by the next refresh);
  refreshes on focus change / toolbar, not on every keystroke.
- **Not visually verified** (no `runIde`). Not a rendered diagram — see item 8.

### 8. ~~Full `DiagramProvider`-based import graph~~ — CLOSED (not built; item 7's Imports tool window is accepted as sufficient)
The originally-scoped Phase 5 item, if item 7 turns out insufficient. Needs
a session that can actually run `./gradlew runIde` and look at the result —
don't attempt this blind again.

### 9. ~~Marketplace-readiness pass~~ — DONE, except what needs a human
Done: `README.md`, `CHANGELOG.md`, a full `plugin.xml` description and
change-notes, `pluginIcon.svg`, `THIRD_PARTY_NOTICES.md` (licenses read from the
shipped artifacts' own POMs, bundled into the jar under `META-INF/`), and
`buildPlugin` produces a valid ~14MB zip. Every README/description claim was
written against the code, including an explicit *Limitations* section.
- **Found and fixed a real compatibility bug:** `plugin.xml` still declared
  `<depends>com.intellij.java</depends>` from the Phase 0 spike though nothing
  uses Java PSI, which would have made the plugin uninstallable in GoLand (where
  most Tanka users are, presumably). Removed along with the matching
  `bundledPlugin("com.intellij.java")`; all tests still pass.
- **Still open before actually publishing — none of these are mine to decide:**
  1. ~~**A `LICENSE` for this project's own code.**~~ **Done: MIT.** `LICENSE`
     added, README updated, and `THIRD_PARTY_NOTICES.md` now reproduces every
     bundled component's license text and copyright line (not just names and
     links, which MIT/BSD/Apache don't accept for a binary distribution). Also
     corrected xz: 1.11 is 0BSD, not Public Domain. Two upstream gaps recorded
     there: scalatags ships no license file (POM says MIT), and lz4-java's
     native libs embed LZ4/xxHash (BSD-2). Optionally add the license URL on the
     Marketplace listing at publish time.
  2. **Screenshots** (Preview, Imports, color page) — needs a running IDE.
  3. **`./gradlew verifyPlugin` across the recommended IDEs** (GoLand, IU, …).
     Dropping the Java dependency makes them plausible targets but they have
     not been run; README states this.
  4. Signing / `publishPlugin` credentials, and a `<vendor url>` if wanted. The pipeline is wired
     (`intellijPlatform { signing; publishing }` in `build.gradle.kts`, `.github/workflows/release.yml`);
     what a human still has to do is listed in `docs/publishing.md`.

### 12. ~~Flag sjsonnet-only std functions (they fail under `tk`)~~ — DONE
`JsonnetSjsonnetOnlyStdInspection` (warning, on by default) flags `std.regexFullMatch`,
`regexPartialMatch`, `regexGlobalReplace`, `regexReplace` and `regexQuoteMeta` — the
only members sjsonnet has and go-jsonnet v0.22 lacks, re-measured by diffing
`std.objectFieldsAll(std)` from both. The message names the portable Tanka native
where there is one (`regexMatch`, `regexSubst`, `escapeStringRegex`), with the caveats
(boolean only, no captures; `regexReplace`'s first-match-only has no equivalent).
- Data in `stdlib/SjsonnetOnlyStd.kt`. `scripts/sjsonnet-conformance.py std` diffs the
  two key sets and exits 1 if the list has drifted — run it after a sjsonnet or
  go-jsonnet bump (it's the "re-check" this item asked for; the script had no such mode).
- Scope is direct `std.name` only (same as std hover/completion): `std['x']` and
  `local s = std; s.x` aren't followed; a local/parameter named `std` is left alone;
  `vendor/` is skipped like the unused-declaration inspection.
- No quick fix: the natives take different arguments and return booleans instead of
  match objects, so an automatic rewrite would change behaviour.
- Tests: `JsonnetSjsonnetOnlyStdInspectionTest` (guards for shadowing and `vendor/`
  mutation-checked), `SjsonnetOnlyStdTest` (every entry is still a sjsonnet member and
  really evaluates in the preview).

### 13. ~~Re-check the "no tracing/debug hook" premise~~ — DONE: the premise was wrong
There **is** a usable hook, found by writing the call rather than reading `javap`.
`Interpreter` and `Evaluator` are non-final, `Interpreter.createEvaluator(...)` is
public, and `Evaluator.visitExpr(Expr, Eval[])` — the evaluator's central dispatcher,
the same place sjsonnet's own `Profiler` hooks in — is public and non-final. A subclass
returning its own `Evaluator` sees every dispatched expression: no reflection, no
patched sjsonnet. `SjsonnetTracingHookTest` is the working example (and the
regression guard: a sjsonnet bump that closes this fails it; mutation-checked).
Verified by running it:
- Each event carries `Expr.pos()` → `currentFile()` + `offset()`, including code in
  imported files and the `Import` expression itself (in-memory importer, two files).
- The hook runs on the evaluating thread, so blocking there *is* a breakpoint: the
  worker parked in `visitExpr`, another thread inspected it, released it, and the
  result was still correct.
- At a pause the `Eval[]` scope array holds the live values (an argument `21` showed up
  as a `Val.Num`); forcing them from the hook worked.
- On a realistic Tanka-style program (functions, `if`, comprehensions, `std.foldl` with
  a lambda, `+` mixins, `self`) every line that does work fired.
Limits found, not yet worked around:
- **Not every expression goes through `visitExpr`.** Eager/pure-arithmetic fast paths
  (`x * 2` on plain numbers, a plain-variable argument, `i + 1` in a comprehension) and
  the static optimizer's constant folding (`std.length("abc")`) skip it. There is no
  `Settings` switch; `StaticOptimizer` is open (`createOptimizer` is public) so folding
  could be neutered, the private fast paths can't. A breakpoint on such a sub-expression
  wouldn't fire; on a function body, field or call it does.
- Some expressions have synthetic positions (`offset() == -1`); skip them.
- Slots have no names. Mapping `Eval[]` indices to variable names means reading them
  off the parsed AST (`ValidId` carries name and slot index) — not tried.
- Order is demand-driven (lazy), so "step" follows forcing, not source order — the same
  chain sjsonnet's own error stack shows.
- Evaluation still runs on the EDT (see item 4); pausing needs a background thread.
Not built. What it would take is in the "not planned" entry below.

---

### 14. ~~Byte-exact `jsonnetfmt` formatting for Reformat Code~~ — DONE (typing side: item 15)
Kotlin port of go-jsonnet v0.22.0's formatter in `fmt/` behind `JsonnetFormattingService`; code-style page with the
jsonnetfmt toggles; default indent 2. Checked against the real `jsonnetfmt` over 5,885 files x 7 option variants with 0
mismatches, plus upstream and hand-written goldens. Details, quirks kept on purpose and the test recipe: AGENTS.md
"Formatter". Follow-ups: items 15-22.

### 15. ~~Block-model (typing) indentation that agrees with `jsonnetfmt`~~ — DONE (residual shapes below)
`JsonnetBlock` was rewritten after `FixIndentation` (Alignment + Indent, `getChildAttributes` for incomplete constructs,
context-aware spacing from the unparser's rules); details and the IntelliJ pitfalls found: AGENTS.md "Block model".
- **Consistency harness** `JsonnetTypingConsistencyTest`: strips each line's indent from jsonnetfmt-canonical text, asks
  `adjustLineIndent`, compares. Upstream + oracle corpus: 415/435 (95.4%, floor 95%); with go-jsonnet's `cpp-jsonnet/examples` +
  `case_studies` (`JSONNET_TYPING_CORPUS=dirA:dirB`): 3433/3472 (98.9%). Misses are written to `build/typing-consistency.txt`.
  Remaining shapes, all deliberate: the port's "strong indent" (`f(a,⏎ b {` / `x + y⏎ + z {` — later lines are based on a
  column, not the line), UTF-8 byte columns in hanging alignment (`unicode_hanging_indent`), a comment before a comma, and the
  bizarre layouts in `cpp_formatting_braces3`.
- `JsonnetTypingTest`: Enter after `{ a: 1,` / `[1,` / `f(a,` / `local x =` / `local a = 1,` / `if c then` / `else` / `a +` / `a:`,
  between `{}`, `[]`, `()`, after `//` comments and inside `|||` blocks (the platform already keeps the previous line's indent
  there), typed `}` `]` `)`. Needed a grammar change: `objectLiteral`/`arrayLiteral`/`binaryTail`/`elseBranch`/`moreBind` are
  pinned so incomplete code keeps its structure.
- `(`...`)` is structural in `JsonnetBraceMatcher`; `JsonnetTypedHandler` re-indents typed `}`/`]`/`)` from the PSI (the platform
  hard-codes `}` and `)` and does nothing for `]`); `JsonnetEnterBetweenBracesDelegate` extends Enter-between-braces to `[]`/`()`.
- Not done: strong-indent shapes above; `Wrap`-based line breaking (jsonnetfmt keeps the user's breaks and so do we).

### 16. ~~PSI lexer/grammar: digit separators (`1_000`)~~ — DONE
`Jsonnet.flex` accepts `_` between digits (`1_000`, `1_0.5_0e1_0`); lexer, parser and engine (sjsonnet evaluates `1_000` as 1000)
tests added, and `JsonnetFormattingServiceTest` pins that a whitespace-only reformat of `{a:1_000}` is refused (the port would write
`1000`) while a normal one rewrites it.

### 17. ~~Reformat must not touch `vendor/` (and dotfiles) by default~~ — DONE
`JsonnetFormattingService.formatDocument` does nothing for files `JsonnetFormatExclusions` matches (a dot-file, a dot-directory or
a `vendor` directory *below the project root*), like `tk fmt`. It is handled inside the service, not via `canFormat`, because
declining would hand the file to the Block-model formatter, which would reformat it. Setting: Code Style > Jsonnet > Other >
"Don't reformat vendor/ and dot-files" (`SKIP_VENDOR_AND_DOTFILES`, default on). It applies to every explicit request, single
file included; turn the option off to format a vendored file on purpose.

### 18. ~~Verify the formatting entry points that only the Reformat Code action covers today~~ — DONE
`JsonnetFormattingServiceTest` now covers: several ranges (`ReformatCodeProcessor(file, ranges)` — what "only changed text" hands
over), directory reformat (skips `vendor/` and non-Jsonnet files), Reformat File + Optimize Imports (no optimizer is registered;
sorting is part of the formatter), the reformat-on-save processor path, and the "Can't format" notification for a PSI-valid file
the port rejects (`{a:1,a:2}`). Reformat-on-save itself (`ActionsOnSaveManager`) needs a real frame and is replayed via the
processor `FormatOnSaveAction` builds; check the real thing in item 19.

### 19. ~~Manual pass in a real IDE~~ — DONE (checked by hand)
`./gradlew runIde`: Ctrl+Alt+L on a real Tanka environment and on k8s-libsonnet files, compare with `tk fmt --stdout`; check
undo restores the file in one step, caret/folds/bookmarks survive, the Code Style > Jsonnet page renders (tabs, preview, the
"Rewrites" group under "Other"), `.editorconfig` `indent_size` is honoured, and that toggling each option changes the preview.

### 20. ~~Formatter performance and limits~~ — DONE
Measured (JIT-warm, `JsonnetFormatter.format` + `JsonnetTextEdits.compute`): the largest real files (k8s-libsonnet 1.34
`cronJob.libsonnet`, 123 KB; also the largest `vendor/` file) format in 4–17 ms, the diff of a no-op takes <1 ms and of a fully
unindented copy 10–60 ms. Synthetic files scale linearly: 4.8 MB (100k fields) 0.43 s format + 0.44 s diff, 20 MB 1.9 s + 2.3 s
(2.8M edits), and `DiffTooBigException` never fired. **No size guard and no off-EDT step**: real Jsonnet is orders of magnitude
below where this matters, and the synchronous path keeps undo/caret handling simple.
- `JsonnetTextEdits` now catches `DiffTooBigException`: one edit around the common prefix/suffix for a whole-file request; a
  selection is left alone (one edit can't be limited to a range).
- Nesting: the limit was far lower than the "10,000 arrays" assumed — the caller's stack overflowed at ~200 levels of `{a:` and
  a few hundred of `[`/`(`. `format()` now retries once on a worker thread with a 512 MB stack (only touched pages are committed;
  the port has no IDE state, so waiting on it is safe) and formats 200,000 levels; beyond that it still refuses with "nested too
  deeply". Tests in `JsonnetFormattingServiceTest` ("item 20").

### 21. ~~Keep the port honest over time~~ — DONE
- `scripts/jsonnetfmt-conformance.py` (`version`, `goldens [--write]`, `fetch`, `differential`); the ported release lives in
  `fmt/PortedFrom.kt` and the script refuses a `jsonnetfmt` of another version. Checked end to end from fresh sparse clones
  (k8s-libsonnet 1.34 + go-jsonnet `testdata` at the tag): 1,410 files x 10 variants, 0 mismatches.
- `.github/workflows/jsonnetfmt-differential.yml`: manual + weekly, installs the pinned `jsonnetfmt`, fetches the corpora, runs
  everything with `--variants all`. Also listed in the release checklist (`docs/publishing.md`).
- `Options.WHITESPACE_ONLY`: `JsonnetFormatterWhitespaceOnlyTest` (property: same non-whitespace characters) over the checked-in
  inputs and any `JSONNETFMT_CORPUS`. It found three lexer/unparser canonicalizations (digit separators, `a[1::]`, `|||-`) that
  no option turns off — documented on the option, exempted in the test, and already refused by the service's guard.
- Variants widened from 7 to 10 (`--indent 8 --pad-arrays`, `--indent 1 --max-blank-lines 3`, explicit `s`/`s` styles with
  `--no-pad-objects --no-use-implicit-plus`): no new mismatches. The differential test no longer excludes
  `error.parse.deep_array_nesting.jsonnet` (item 20's bigger stack makes the port format it like jsonnetfmt).

### 22. ~~Adjacent typing niceties (not formatting proper)~~ — DONE
- **Quotes**: `JsonnetQuoteHandler` (registered for both `Jsonnet` and `Libsonnet` file types) pairs `'`/`"`, types over the closing
  one, doesn't pair after a word character (`it's`), in comments or inside the other kind of string; Backspace on `'|'` removes
  both. The lexer has no unterminated-string token — a fresh lone quote is a `BAD_CHARACTER` — so `isOpeningQuote` /
  `hasNonClosedLiteral` treat that as the opening quote. Text blocks and `@'..'` aren't paired.
- **Comments**: it was *not* a platform default — Enter in the middle of `// a b` gave no continuation. `JsonnetEnterInLineCommentHandler`
  continues `//` and `#` comments (the platform's own handler needs a `CodeDocumentationAwareCommenter`, and line and block
  comments are one `COMMENT` token, so it would have inserted `//` into `/* */`). Enter at the end of a comment and in a block
  comment stays the platform's. Tests in `JsonnetTypingTest` ("item 22").
- Not done: `*` continuation inside `/* */`.

## Explicitly not planned (revisit only if the premise changes)

- **Online completion for `jsonnetfile.json` package names/versions** —
  dropped, not deferred. `jb` resolves dependencies by direct git remote URL,
  not by name/version against a central registry; there's nothing to
  complete against. Revisit only if Tanka/`jb` ever grows a real package
  index.
- **`helmTemplate` / `kustomizeBuild` natives on the JVM** — they shell out to
  external binaries even inside real Tanka, so a JVM version would just be a
  worse `tk`. (Tanka's *pure* natives are done — item 10.)
- **Real breakpoint/step-through debugging into `sjsonnet`** — the premise that
  made this impossible ("no public hook") was wrong; see item 13, where the hook is
  probed and pinned by `SjsonnetTracingHookTest`. Still not built, and it's a phase of
  its own: an `XDebugProcess`/run configuration, a breakpoint type mapped to
  `Position`s, a suspend/resume protocol (the pausing mechanics are the easy part),
  variable names for the scope view, evaluation off the EDT, and living with the
  fast-path gaps above. `EvaluateJsonnetExpressionAction` (Phase 5) remains the
  substitute. Revisit if step-through becomes a priority.
- **Inferred merge-result inlay hints on composed objects** — dropped as
  fundamentally imprecise without full evaluation (computed field names,
  imports, and comprehensions all defeat static inference). The Preview tool
  window is the precise answer to "what does this evaluate to"; a second,
  approximate answer isn't worth the false-hint risk.
