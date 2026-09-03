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

### 1. Get `BasePlatformTestCase` working in this environment
The single most recurring gap across Phases 2, 4, and 5: every write-path
feature (rename, `setName`/`handleElementRename`, the formatter's
`CodeStyleManager.reformat()` call, the stub-tree build/deserialize
machinery from the Phase 4 stub index, the `JsonnetUnusedDeclarationInspection`
quick fixes) is **reviewed but not automated-tested**, because this fixture
hung indefinitely (16+ min, no output) every time it was tried and was
killed rather than debugged. Nobody has actually confirmed rename or
reformat work end-to-end in a real IDE session.
- **Why P0:** it's not that a feature is missing — it's that several
  features' correctness is simply unknown. A silent regression in any of
  them would ship undetected.
- **Where to start:** `AGENTS.md`'s "Testing" section has the exact repro
  history. First step should be root-causing the hang itself (headless mode
  flag? missing display server? a specific service — `CommandProcessor`,
  `PsiDocumentManager` — deadlocking on init?) rather than trying the whole
  fixture again and re-killing it. Consider running it with a hard time-box
  in a background task and capturing thread dumps on timeout.
- **Payoff:** retroactively covers Phase 2 (VFS importer), Phase 4 (rename,
  formatter), and Phase 5 (inspection quick fixes) in one unblock.

### 2. Validate the stub index against a real large Tanka/vendor tree
The Phase 4 stub-index layer (`lang/stubs/`) was built specifically to avoid
the plan's own predicted risk ("stub-indexing huge `vendor/` trees... could
be slow") — but that risk was never actually measured. All coverage is
`JsonnetStubIndexUtilTest` (pure logic, no real indexing). Nobody has opened
an actual `jsonnet-libs`-sized checkout and confirmed indexing stays fast,
`vendor/` exclusion actually keeps the index small, or "Navigate > Symbol"
returns sane results at scale.
- **Why P0-adjacent:** the whole feature was justified by a performance
  claim that's still unverified — if it doesn't actually help, that's worth
  knowing before more is built on top of it (e.g. quick-fixes that assume a
  cheap global index).
- **Where to start:** clone a real Tanka repo with a populated `vendor/`
  (or synthesize one — hundreds of `.libsonnet` files with nested objects),
  open it in a sandbox IDE run (`./gradlew runIde`), and check indexing time
  + memory + Go to Symbol result quality.

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
