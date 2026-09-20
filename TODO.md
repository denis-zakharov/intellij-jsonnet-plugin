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

### 3. Wire sjsonnet's own `StaticOptimizer` for unresolved-reference diagnostics
`JsonnetUnresolvedReferenceAnnotator` currently sources "is this identifier
resolved" from the plugin's own hand-rolled `JsonnetResolver` lexical-scope
walk (Phase 1), not from sjsonnet's actual static analysis — noted as a
follow-up since Phase 2 and never revisited. The plan's own §4.4 rationale
("reuse sjsonnet's own semantics... so diagnostics are guaranteed consistent
with what a full evaluation would report") isn't actually true yet for this
specific diagnostic.
- **Why P0-adjacent:** a hand-rolled resolver can diverge from real Jsonnet
  scoping rules in edge cases (the same class of risk that produced the
  Phase 5 `JsonnetResolver.resolveLocalName` object-local bug — see
  AGENTS.md). Every other "correctness" surface in the plugin (evaluation,
  go-to-definition targets) already defers to sjsonnet; this one doesn't.
- **Where to start:** confirm via `javap` (same technique as the native-fn
  and stub-index investigations) whether `sjsonnet`'s `StaticOptimizer`/
  `Scope` classes expose anything usable from Kotlin before assuming it's
  wireable — this was never actually spiked, only deferred.

---

## P1 — Real, scoped feature gaps

### 4. Preview tool window: TLA vars, YAML toggle, output→source click-jump
`JsonnetPreviewPanel` (Phase 2) only supports `--ext-*` vars. TLA vars
(`--tla-*`), a YAML output mode, and click-to-jump from output back to the
source position that produced it were all explicitly scoped out as "not v1"
and never revisited.
- **Effort:** moderate. TLA vars is a small UI+engine-call addition
  (`JsonnetEngine.evaluateFile` would need a `tlaVars` param wired through —
  check whether `Interpreter`'s constructor already threads a TLA map, since
  the pattern's very similar to `extVars`). Output→source click-jump is the
  hard part — it needs the §4.4 "TextRange → sjsonnet Expr" position mapping
  that's never actually been built for anything.

### 5. Preview/import resolution should honor unsaved buffers for imported files
`VirtualFileImporter`/`VirtualFilePath` (Phase 2) read imports straight off
the VFS/disk — edits to a file other than the one currently focused don't
show up in Preview until saved. Called out as a known simplification, not
revisited.
- **Effort:** moderate — needs `FileDocumentManager.getDocument(file).text`
  preferred over `VirtualFile.contentsToByteArray()` in the importer's read
  path.

### 6. `ColorSettingsPage` for the Phase 5 semantic-highlighting keys
`JsonnetSemanticHighlightingAnnotator`'s four new `TextAttributesKey`s
(`JSONNET_LOCAL_VARIABLE`/`PARAMETER`/`FIELD`/`STD_CALL`) only have fallback
colors — there's no Settings > Editor > Color Scheme page entry for a user
to customize them independently of the fallback.
- **Effort:** low-moderate — standard `ColorSettingsPage` implementation
  (demo text + attribute descriptor map), well-trodden IntelliJ-plugin
  boilerplate.

---

## P2 — Stretch items deferred in the plan doc

### 7. Lightweight import-graph view (not the full `DiagramProvider`)
Phase 5's import-graph item was deferred outright because `com.intellij
.diagram`'s `DiagramProvider` framework is heavy and version-fragile, and
this environment can't visually verify one. A much cheaper alternative:
a simple read-only panel (reusing `TankaJpath`'s already-working resolution)
listing import edges as a tree or a basic rendered graph, with no dependency
on the diagram framework at all.
- **Effort:** low-moderate for a tree-view version; the full `DiagramProvider`
  version stays a separate, larger, higher-risk task — do this instead unless
  a session specifically wants the "real" diagram integration and can test it
  live.

### 8. Full `DiagramProvider`-based import graph
The originally-scoped Phase 5 item, if item 7 turns out insufficient. Needs
a session that can actually run `./gradlew runIde` and look at the result —
don't attempt this blind again.

### 9. Marketplace-readiness pass
README, CHANGELOG, plugin description/screenshots, and a final pass over
`plugin.xml` metadata (`<description>`, vendor info) — separate from feature
work but named as part of Phase 4's original exit criterion ("ready for a
JetBrains Marketplace listing") and never done. Low risk, low urgency until
an actual listing is planned.

---

## Explicitly not planned (revisit only if the premise changes)

- **Online completion for `jsonnetfile.json` package names/versions** —
  dropped, not deferred. `jb` resolves dependencies by direct git remote URL,
  not by name/version against a central registry; there's nothing to
  complete against. Revisit only if Tanka/`jb` ever grows a real package
  index.
- **Native `std.native(...)` functions with real JVM re-implementations**
  (Tanka's Go-injected `parseYaml`, `manifestJsonFromJson`, etc.) — confirmed
  via `javap` that `sjsonnet`'s public API has no registration hook for
  these. Revisit only if a future `sjsonnet` release adds one; until then
  these can only ever run through the ground-truth `tk` shell-out tier.
- **Real breakpoint/step-through debugging into `sjsonnet`** — same
  no-public-hook finding as above. `EvaluateJsonnetExpressionAction`
  (Phase 5) is the honest substitute. Revisit only if `sjsonnet` ever exposes
  an evaluation-tracing API.
- **Inferred merge-result inlay hints on composed objects** — dropped as
  fundamentally imprecise without full evaluation (computed field names,
  imports, and comprehensions all defeat static inference). The Preview tool
  window is the precise answer to "what does this evaluate to"; a second,
  approximate answer isn't worth the false-hint risk.
