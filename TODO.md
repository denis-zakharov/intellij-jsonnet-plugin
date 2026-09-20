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
- Still evaluates on the EDT (pre-existing); a slow evaluation blocks the UI.

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

### 8. Full `DiagramProvider`-based import graph
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
  1. **A `LICENSE` for this project's own code.** None exists; README says so.
  2. **Screenshots** (Preview, Imports, color page) — needs a running IDE.
  3. **`./gradlew verifyPlugin` across the recommended IDEs** (GoLand, IU, …).
     Dropping the Java dependency makes them plausible targets but they have
     not been run; README states this.
  4. Signing / `publishPlugin` credentials, and a `<vendor url>` if wanted.

### 12. Flag sjsonnet-only std functions (they fail under `tk`)
sjsonnet ships `std.regexFullMatch`, `regexPartialMatch`, `regexGlobalReplace`,
`regexReplace` and `regexQuoteMeta`; go-jsonnet v0.22 has none of them. Code
using them previews fine, then fails in `tk show` — the opposite of the usual
gap, and silent. A weak-warning inspection on `std.<those>` (Tanka's own
`regexMatch`/`regexSubst` natives are the portable equivalents) would close it.
Re-check the list with `scripts/sjsonnet-conformance.py` first; go-jsonnet may
have added them since.

### 13. Re-check the "no tracing/debug hook" premise
The "Explicitly not planned" debugger entry rested on the same shallow public-API
inspection that wrongly ruled out native functions. `SjsonnetMainBase.mainConfigured`
takes a `DebugStats` and an `Option[Evaluator]`, and `Interpreter` takes a logger
and `storePos`; whether any of that is a usable evaluation-tracing hook is
unchecked. Spend a probe (write the call, don't just read `javap`) before
treating step-through as impossible.

---

## Explicitly not planned (revisit only if the premise changes)

- **Online completion for `jsonnetfile.json` package names/versions** —
  dropped, not deferred. `jb` resolves dependencies by direct git remote URL,
  not by name/version against a central registry; there's nothing to
  complete against. Revisit only if Tanka/`jb` ever grows a real package
  index.
- **`helmTemplate` / `kustomizeBuild` natives on the JVM** — they shell out to
  external binaries even inside real Tanka, so a JVM version would just be a
  worse `tk`. (Tanka's *pure* natives are done — item 10.)
- **Real breakpoint/step-through debugging into `sjsonnet`** — no public hook
  found, **but see item 13: that check was shallow.** `EvaluateJsonnetExpressionAction`
  (Phase 5) is the honest substitute. Revisit only if `sjsonnet` ever exposes
  an evaluation-tracing API.
- **Inferred merge-result inlay hints on composed objects** — dropped as
  fundamentally imprecise without full evaluation (computed field names,
  imports, and comprehensions all defeat static inference). The Preview tool
  window is the precise answer to "what does this evaluate to"; a second,
  approximate answer isn't worth the false-hint risk.
