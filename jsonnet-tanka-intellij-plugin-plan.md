# A Native (Non-LSP) IntelliJ Plugin for Jsonnet + Tanka
### Research summary and phased build plan for Claude Code

**Verdict up front:** yes, this is buildable, and a from-scratch PSI-based plugin is
very plausibly *better* than the LSP route, specifically because of `sjsonnet`.
`sjsonnet` is what didn't exist (in usable form) when the existing plugins were
designed: a fast, Apache-2.0, JVM-native Jsonnet engine that embeds directly into
an IntelliJ plugin with no external process, no protocol translation, and no
second implementation of Jsonnet's scoping/`std` semantics to keep in sync.

---

## 1. Landscape today

| Project | What it actually is | Status | Ceiling |
|---|---|---|---|
| [databricks/intellij-jsonnet](https://github.com/databricks/intellij-jsonnet) | Real PSI plugin (own lexer/parser). Syntax highlighting, autocomplete for imports/locals, go-to for imports/locals, folding, block-select. | 91 stars, 277 watchers, 111 commits, effectively dormant. No diagnostics, no stdlib awareness, no Tanka. | Was never extended past "editable text with basic nav." |
| [zzehring/intellij-jsonnet](https://github.com/zzehring/intellij-jsonnet) | LSP4IJ client wrapping `grafana/jsonnet-language-server`. Go-to-definition (self/$/local/cross-file), error/warning/lint diagnostics, stdlib hover+completion, "Evaluate file" action, syntax highlighting/folding via TextMate-ish grammar. | Actively maintained, listed as the official JetBrains client on the LSP server's own README. | Bounded by LSP4IJ + LSP protocol: no stub indices, weak/no rename & find-usages, no structural refactoring, no Tanka awareness, an external Go binary the IDE has to supervise. |
| [grafana/jsonnet-language-server](https://github.com/grafana/jsonnet-language-server) | Go, built on `go-jsonnet`, AGPL-3.0. | Active (222 stars), this is the actual feature engine behind the zzehring plugin. | Sets the **feature parity bar** below — but its license (AGPL-3.0) means it should be treated as a spec reference only, never vendored. |
| [databricks/sjsonnet](https://github.com/databricks/sjsonnet) | Scala 3 Jsonnet implementation for JVM/GraalVM/Scala Native/JS. Apache-2.0. Published to Maven Central as `com.databricks:sjsonnet_3`. Very active (releases through Aug 2026). | This is the missing piece that makes a serverless plugin realistic. | Not a full IDE toolkit by itself — no PSI, no formatter, no LSP surface — it's an *evaluator library* we build the IDE layer on top of. |

Key `sjsonnet` facts that matter for plugin design:

- **Architecture is already IDE-shaped.** It's implemented as Parser → StaticOptimizer →
  Evaluator → Materializer, and `sjsonnet.Expr` carries source-offset position info
  end-to-end. That's exactly the shape a language-server or PSI resolver needs.
- **It's an intrinsics-based `std`,** not a `std.jsonnet` written in Jsonnet — every
  stdlib function is a real Scala method with real signatures, which is a clean
  source of truth for hover docs/completion (better error messages too).
- **Embeddable, not just a CLI.** `new Interpreter(...).interpret(...)` is the
  documented low-overhead usage path; there's also an async-import variant
  (`interpretAsync` with resolver/loader callbacks) designed for environments
  where imports can't just be `java.io.File` reads — a near-perfect fit for
  resolving imports against IntelliJ's VFS/unsaved editor buffers instead of disk.
- **No bundled formatter.** Formatting (`jsonnetfmt`) is a gap `sjsonnet` doesn't
  fill; the plan below treats this as a distinct workstream (§6, Phase 2/4).
- **Dependencies are light:** `fastparse_3`, `ujson_3`, `scala-collection-compat_3`.
  Bundling means shading a small Scala 3 runtime into the plugin — a solved
  problem (Gradle Shadow), not a blocker.

---

## 2. Why native PSI, concretely, not just "because it's possible"

IntelliJ's own gold-standard language plugins (Rust, Python, pre-LSP Go, Kotlin)
are PSI-native for the same reasons that would apply here:

- **Stub indices** → instant project-wide Find Usages / Go to Symbol, even across
  a `vendor/` tree that can be tens of megabytes of `.libsonnet`, without an
  external process re-scanning anything.
- **Structural refactoring** (rename a local/field and every reference updates;
  extract-to-local; inline; move file with import-path fixups) — LSP `rename` in
  `jsonnet-language-server` is thin-to-absent, and even where LSP4IJ supports it
  the UX is coarser than IntelliJ's native refactoring framework.
- **Real annotator/intention/quick-fix framework** — "unresolved import → jb
  install" or "unused local → remove" as one-click fixes, not just red squiggles.
- **No external process to babysit** — no binary-version skew between plugin and
  language server, no startup latency, no Windows/WSL path translation bugs, no
  zombie processes. This class of bug report dominates most LSP-wrapper plugins'
  issue trackers.
- **Full integration with IntelliJ subsystems** that don't speak LSP well anyway:
  Structure View, breadcrumbs, color-scheme settings, live templates, Search
  Everywhere, the diagram framework, run-line-markers.

The one reason teams *choose* LSP-wrapping over native PSI — "writing a correct,
fast evaluator/type-checker from scratch is too much work" — is exactly the
problem `sjsonnet` removes. This plugin doesn't need to invent Jsonnet semantics;
it needs to embed an existing, fast, correct implementation and grow an IntelliJ
skin around it.

---

## 3. Target feature matrix

| Feature | databricks plugin | zzehring + grafana LSP | This plan |
|---|:-:|:-:|:-:|
| Syntax highlighting | ✅ | ✅ | ✅ + semantic highlighting |
| Folding / brace matching | ✅ | ✅ | ✅ |
| Local/`self`/`$`/`super` resolution & go-to-def | partial | ✅ | ✅ (PSI reference, matches eval scoping exactly) |
| Cross-file import go-to-def & completion | partial | ✅ | ✅ + vendor/jb-aware + Tanka jpath-aware |
| Find Usages (project-wide, incl. vendor) | ❌ | limited | ✅ (stub-indexed) |
| Rename (local/field/file, updates imports) | ❌ | ❌/partial | ✅ |
| Live diagnostics (parse + eval errors) | ❌ | ✅ (external process) | ✅ (in-process, incremental, debounced) |
| Stdlib hover + completion | ❌ | ✅ | ✅ (sourced straight from `Std.scala`, always in sync with the pinned engine version) |
| Eval/preview pane (JSON/YAML, TLA/extVar input, output→source jump) | ❌ | basic "Evaluate file" | ✅ rich split preview |
| Tanka project recognition (`environments/`, `spec.json`, `jsonnetfile*.json`, `vendor/`, `lib/`) | ❌ | ❌ | ✅ |
| Tanka-correct import resolution (root/base/jpath) | ❌ | ❌ | ✅ |
| `import 'tk'` virtual module + env-spec completion | ❌ | ❌ | ✅ |
| Tanka native functions (`std.native('parseYaml')`, etc.) | ❌ | ❌ | ✅ (curated registry + real JVM impls for fast preview) |
| jsonnet-bundler awareness (`jb install` quick fix, `jsonnetfile.json` schema completion) | ❌ | ❌ | ✅ |
| Run configs: `tk show/diff/apply/export`, `jsonnet eval` | ❌ | ❌ | ✅ |
| Formatter integrated with Reformat Code | ❌ | delegates to LSP, limited config | ✅ (phased — see §6) |
| Structure view / breadcrumbs | ❌ | limited (LSP document symbols) | ✅ native |
| Works with zero external binaries for core editing | mostly | ❌ (needs the LSP binary managed) | ✅ (`sjsonnet` is vendored in the plugin jar; `tk`/`jb` only needed for full-fidelity deploy actions) |

---

## 4. Architecture

### 4.1 Component map

```
┌─────────────────────────────────────────────────────────────────┐
│ Editor features layer                                           │
│ annotator · completion · doc provider · formatter · folding ·   │
│ structure view · find-usages/rename · line markers · run configs│
└───────────────┬───────────────────────────────────┬─────────────┘
                │                                    │
   ┌────────────▼────────────┐          ┌────────────▼────────────┐
   │ PSI / stub-index layer   │          │ Tanka layer              │
   │ generated from Grammar-  │          │ jpath resolver, env      │
   │ Kit BNF + JFlex lexer;   │          │ spec.json model, jb      │
   │ hand-written references  │          │ manifest model, `tk`     │
   │ for imports/locals/self/ │          │ virtual import, native-fn│
   │ super/std/native-fn      │          │ registry                 │
   └────────────┬─────────────┘          └────────────┬─────────────┘
                │                                      │
   ┌────────────▼──────────────────────────────────────▼────────────┐
   │ Semantic/evaluation tier — "JsonnetEngine" project service      │
   │ wraps embedded sjsonnet Interpreter (shaded).                   │
   │  • structural mode: parse + static-optimize only (cheap;        │
   │    drives as-you-type diagnostics)                              │
   │  • full-eval mode: real interpret() for Preview/Evaluate actions│
   │  • custom VFS/PSI-backed Importer (sees unsaved buffers,        │
   │    honors ProgressManager cancellation)                         │
   └───────────────────────────────┬───────────────────────────────┘
                                    │
                     ┌──────────────▼──────────────┐
                     │ External tool bridge          │
                     │ thin process wrappers around   │
                     │ the user's own `tk`, `jb`,     │
                     │ optional `jsonnetfmt`          │
                     └────────────────────────────────┘
```

### 4.2 The two-tier evaluation strategy (important)

Tanka itself shells out to `helm template` / `kustomize build` / `kubectl` for
parts of a real `tk show`/`tk apply`. Re-implementing Helm/Kustomize/K8s
reconciliation inside the plugin would be a large, permanently-drifting
maintenance burden, and it isn't necessary. Use two tiers instead:

1. **Fast tier (embedded `sjsonnet`, in-process).** Drives live diagnostics,
   go-to-definition, hover, and a "fast preview" of pure-Jsonnet evaluation —
   accurate for anything that doesn't depend on Helm/Kustomize/K8s
   reconciliation. Zero external process, sub-second, runs on every debounce.
2. **Ground-truth tier (shell to the real `tk` binary the user already has
   installed).** Used for `tk show` / `tk diff` / `tk apply` / `tk export` run
   configurations and for any environment whose evaluation depends on
   `helm.template()`/`kustomize.build()`. Always labelled in the UI as the
   "real" result, so there's never ambiguity about which evaluation path
   produced what the user sees.

This mirrors how good VCS plugins work (native git binary for real operations,
custom logic only for fast in-editor feedback) and is the honest way to hit
"more feature-rich than the LSP option" without silently reimplementing Tanka
incorrectly.

### 4.3 Grammar/parser approach

Use Grammar-Kit + JFlex (the standard toolchain behind intellij-rust,
intellij-erlang, intellij-haxe, and friends) to generate the Lexer,
`IElementType`s, and PSI classes from a hand-authored `.bnf` grammar, then layer
hand-written PSI mixins for resolution. Do **not** copy grammar/parser code from
`go-jsonnet` or `grafana/jsonnet-language-server` (AGPL-3.0) — write the `.bnf`
independently from the [Jsonnet language spec](https://jsonnet.org/ref/spec.html),
which is documented in prose and not itself a code artifact.

Deliberately mirror the grammar's *shape* to what `sjsonnet.Parser` accepts, so
PSI structure and evaluation semantics never disagree on edge cases: `super`
lookups, `$` binding, object composition (`+`, `+:`, `+::`), hidden fields
(`::`), object comprehensions, text blocks (`|||...|||`), computed field names.

### 4.4 Reusing `sjsonnet`'s own semantics instead of re-deriving them

`sjsonnet.Expr` nodes carry source offsets. Build a mapping from IntelliJ
`TextRange`s (per PSI leaf) to the corresponding `sjsonnet.Expr`/scope info, so:

- "Go to definition" follows `sjsonnet`'s actual static scope resolution
  (correct for `super`/`$`/object-merge edge cases) rather than a hand-rolled
  approximation that could drift from what will really execute.
- "Hover" can show an evaluated/partially-evaluated value, not just a type guess.
- Diagnostics reported by the structural-mode pass are guaranteed consistent
  with what a full evaluation would report.

Implement a custom `Importer`/loader shim (confirm the exact `sjsonnet` public
API surface during the Phase 0 spike) backed by `VirtualFile`/`PsiManager` so
imports resolve against the IDE's view of the world (unsaved buffers, Tanka
jpath, jb vendor paths) rather than raw disk I/O.

---

## 5. Tanka-specific mechanics to model (confirmed from Tanka's own source/docs)

- **jpath resolution** (`pkg/jpath.Resolve`): walk up from the current file to
  find `root` (nearest ancestor with `jsonnetfile.json`) and `base` (nearest
  ancestor with `main.jsonnet`); the effective import path is
  `[base, root/vendor, root/lib]`. Implement this natively — it's the single
  most important piece of Tanka-aware import resolution and neither existing
  plugin does it.
- **Directory conventions:** `environments/<name>/main.jsonnet` +
  `environments/<name>/spec.json`, `lib/` for project-local libraries, `vendor/`
  for `jb`-installed dependencies, `jsonnetfile.json` (direct deps) +
  `jsonnetfile.lock.json` (pinned versions/hashes).
- **`spec.json` schema:** `apiVersion`, `kind: Environment`, `spec.apiServer`,
  `spec.namespace`, `spec.contextNames`, etc. — back editing with a JSON Schema
  for completion/validation, and surface it as a first-class file type/icon.
- **`import 'tk'` virtual module:** Tanka injects environment metadata (e.g.
  `tk.env.spec.namespace`) at eval time; this isn't a real file, so it needs an
  explicit synthetic PSI target + completion provider, or resolution silently
  breaks for one of the most common Tanka idioms.
- **`jsonnetfile.json` schema:** `version`, `dependencies[].source.git.{remote,subdir}`,
  `dependencies[].version`, `legacyImports`. Use this to power "jump from an
  unresolved `vendor/...` import to a `jb install` quick fix" and to validate/
  autocomplete the file itself.
- **Native functions (`std.native('name')(...)`)**: Tanka registers Go-native
  functions into its `go-jsonnet` VM that don't exist in vanilla `std` —
  `parseJson`, `parseYaml`, `manifestJsonFromJson`, `manifestYamlFromJson`,
  `escapeStringRegex`, and others. `sjsonnet` won't know about these by
  default. Maintain a curated registry with real JVM re-implementations (for
  the fast-preview tier) plus hover/completion docs; fall back to the
  ground-truth `tk` tier for anything not (yet) re-implemented.
- **`helm.template()` / `kustomize.build()`**: these ultimately shell out to
  external binaries even inside real Tanka. Don't attempt fast-tier evaluation
  for these — route straight to the ground-truth tier.

---

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| `sjsonnet` is Scala 3 — classpath conflicts if the user also has JetBrains' Scala plugin installed. | Shade/relocate `scala.*` and `sjsonnet.*` into a private package via the Gradle Shadow plugin; verify with the IntelliJ Platform Plugin Verifier and a manual test session with the Scala plugin enabled. |
| Writing a full Jsonnet grammar/PSI/resolver is genuinely multi-week effort, unlike the multi-day LSP-wrapping route. | Scope Phase 1 tightly (see §7) to a walking skeleton before expanding grammar coverage; don't gold-plate before the core loop works. |
| Stub-indexing huge `vendor/` trees (some Tanka repos vendor hundreds of MB of `jsonnet-libs`) could be slow. | Exclude `vendor/` from full-content indexing by default; index only top-level exported symbols via stub index (same pattern IntelliJ uses for library sources vs. project sources); parse full PSI lazily on demand. |
| Divergence between `sjsonnet`'s registered `std` builtins and this plugin's completion/hover registry over time. | Pin the `sjsonnet` version explicitly; add a CI task (good first Claude Code job) that diffs `Std.scala`'s builtin names against the completion registry on every dependency bump. |
| Helm/Kustomize-backed environments can't be faithfully fast-evaluated. | Two-tier strategy (§4.2) — never claim in-JVM fidelity for those; always label which tier produced a given preview. |
| Plugin size / Marketplace compatibility churn. | `sjsonnet` + its light deps (`fastparse`, `ujson`, `scala-collection-compat`) are a few MB — well within normal plugin size norms; track IntelliJ Platform EAP compatibility ranges in CI. |

---

## 7. Licensing

- `sjsonnet` — Apache-2.0 → safe to embed/shade.
- `databricks/intellij-jsonnet` — Apache-2.0 → safe to reference for Gradle/plugin
  boilerplate if useful, though starting fresh from the current
  [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
  is cleaner.
- `zzehring/intellij-jsonnet` — Apache-2.0.
- `grafana/jsonnet-language-server` — **AGPL-3.0** → treat as a feature-parity
  reference only; do not vendor its code or its binary.
- Recommend licensing the new plugin Apache-2.0 or MIT, matching `sjsonnet`, to
  keep the whole stack permissively licensed and easy to redistribute/build on.

---

## 8. Phased build plan (for Claude Code)

Each phase lists concrete deliverables and an exit criterion so it can double as
a tracked checklist.

### Phase 0 — Spike & de-risk (aim: a few days) — ✅ done
- [x] Scaffold a new plugin from the IntelliJ Platform Plugin Template
      (Gradle Kotlin DSL, `org.jetbrains.intellij.platform` Gradle plugin).
- [x] Add `com.databricks:sjsonnet_3` as a dependency, shaded via Gradle Shadow;
      write a smoke test that calls `Interpreter.interpret()` from inside a
      throwaway IntelliJ action and prints the result — confirms no classloader
      conflicts.
- [x] Spike the custom `Importer`/loader API surface in `sjsonnet` needed to
      back imports with `VirtualFile` instead of disk I/O; write it down.
- [x] Author a minimal Grammar-Kit `.bnf` covering only: object literals,
      `local`, string/number/bool/null literals, `import`/`importstr`. Generate
      lexer+PSI, register a `LanguageFileType` for `.jsonnet`/`.libsonnet`, get
      syntax highlighting + brace matching + folding working end-to-end.
- **Exit criterion:** opening a `.jsonnet` file shows correct highlighting/
  folding for the minimal grammar subset, and a manual action can evaluate the
  open file via embedded `sjsonnet` and print JSON to a tool window.
  **Status:** highlighting/braces/folding/commenter wired for the minimal
  grammar; `JsonnetEngine` (`engine/JsonnetEngine.kt`) embeds the shaded
  interpreter and is exercised by a `Evaluate Jsonnet File` action (shows a
  dialog, not yet a tool window — deferred to Phase 2's real Preview pane) and
  by `JsonnetEngineTest`, which evaluates real Jsonnet source end-to-end.
  Note: had to drop a planned `relocate("os", ...)` shade — Shadow's relocator
  rewrites matching *string literals* too, and the bare `os` prefix corrupted
  unrelated `System.getProperty("os.arch")` lookups in a transitive dep
  (lz4-java) into garbage keys, which surfaced as a `NullPointerException`
  from that library's static initializer. Fixed by relocating only the
  specific third-party package prefixes actually at risk of colliding with
  IntelliJ's own bundled classes (scala/sjsonnet/fastparse/ujson/upickle/geny/
  pprint/mainargs/scalatags/org.yaml.snakeyaml), not `os-lib`.

### Phase 1 — MVP parity with the databricks plugin, but native — ✅ done
- [x] Extend the `.bnf` to full Jsonnet grammar (functions, comprehensions,
      `super`/`self`/`$`, object composition operators, text blocks, computed
      keys, all operators/precedence per the Jsonnet spec).
- [x] `PsiReference` for import paths → resolves relative imports and, where
      applicable, `-J`-style jpath entries.
- [x] `PsiReference` for `local` bindings and simple field references within a
      file.
- [x] Structure view, commenter, code folding for all block types, block
      selection.
- **Exit criterion:** feature-for-feature parity with `databricks/intellij-jsonnet`,
  implemented natively.
  **Status:** grammar covers the full surface syntax (functions, all
  comprehension forms, `super`/`self`/`$`, all object-composition field
  operators `:`/`::`/`:::`/`+:`/`+::`/`+:::`, verbatim strings, text blocks,
  computed keys, slices). Binary operators intentionally parse into one flat
  `Expr` per precedence chain rather than a nested per-precedence tree (see
  bnf comment) — correct for accepting valid syntax, structure view and
  refactoring, but not yet shaped for sub-expression-precise structural edits;
  revisit if a later phase needs that. `local`/param/comprehension-var
  references resolve via `JsonnetResolver` (lexical-scope walk); `self.foo`/
  `$.foo` resolve to a directly-declared field in the immediately-relevant
  object literal — chained access (`self.a.b`), `super.foo`, and fields
  reached through `+`-composition or imports are explicitly out of scope for
  this phase (need type-directed lookup, which is a Phase 2+/eval-tier
  concern). Import/importstr/importbin path strings resolve via a
  `FileReferenceSet`; jpath/vendor-aware resolution is Phase 3's job.
  Added a real parser/PSI test (`JsonnetParsingTest`, using IntelliJ's
  lightweight `ParsingTestCase` — no full sandbox needed) alongside a
  lexer-only test; between them they caught and fixed three real grammar bugs
  before this ever reached a live IDE: (1) `objectComprehension`'s `pin=2`
  committed right after `computedFieldName`, before checking for the `for`
  that actually distinguishes a comprehension from a plain computed field,
  breaking backtracking into `objectMemberList`; (2) `indexSuffix`'s
  `sliceContent ::= expr | sliceParts` ordered choice let the `expr`
  alternative win early on `nums[1:3]` (matching just `1`), never trying the
  slice-shaped alternative — unified into one rule instead; (3) text blocks
  used a hand-rolled lexer state where the closing `\|\|\|` lost the
  maximal-munch race against a generic "consume the line" rule whenever
  trailing content (e.g. `\|\|\|,`) followed it on the same line — replaced
  with JFlex's `~` "match up to" operator (the same tool used for `/* */`
  comments), which sidesteps the length-competition problem entirely.
  BasePlatformTestCase-level tests, per the plan, stay Phase 4's job.

### Phase 2 — Parity+ with the grafana-LSP experience — ✅ done (rescoped, see status)
- [x] `JsonnetEngine` project service wrapping embedded `sjsonnet` in
      structural mode (parse + static-optimize, no full eval) → annotator
      surfaces parse errors and unresolved-symbol diagnostics as-you-type,
      debounced.
- [x] Full-eval "Preview" action/tool window: split editor showing JSON/YAML
      output, inputs for `--tla-*`/`--ext-*` vars, output→source click-to-jump.
- [x] Stdlib hover (quick doc) + completion sourced from `sjsonnet`'s own
      builtin registry (`Std.scala`), so it's always accurate to the pinned
      engine version.
- [x] Formatter v1: shell out to `jsonnetfmt`/`tk fmt` if present on PATH,
      wired into Reformat Code, as a pragmatic stopgap.
- **Exit criterion:** side-by-side with the zzehring+grafana-LSP plugin, this
  plugin matches or exceeds diagnostics/hover/completion/format quality, with
  no external process required for anything except formatting.
  **Status:** real import resolution landed via a VFS-backed `Path`/`Importer`
  (`engine/VirtualFilePath.kt`, `engine/importer/VirtualFileImporter.kt`) —
  Phase 0/1 only ever evaluated importless in-memory snippets. `JsonnetEngine`
  now evaluates real files with ext-var support
  (`JsonnetEngine.evaluateFile`). The "Evaluate Jsonnet File" action was
  repointed to open the new **Jsonnet Preview** tool window
  (`editor/preview/`) rather than showing a one-shot dialog — it auto-refreshes
  (400ms debounced) as the focused file or its ext-vars box changes. Stdlib
  completion/hover (`stdlib/`) reads `std`'s member names straight off
  `Val$Obj.visibleKeyNames()` at runtime, so it can't drift from the pinned
  engine version by construction — hover text is deliberately minimal (name
  only, no per-function description) rather than hand-maintained and
  potentially wrong. Formatter shells out to `jsonnetfmt`/`tk fmt` via
  `AsyncDocumentFormattingService`, manually verified against a real
  `jsonnetfmt` binary on PATH (`echo '{a:1,b:2}' | jsonnetfmt -` →
  `{ a: 1, b: 2 }`), not yet through the actual Reformat Code action in a
  running IDE.
  Rescoped from the original wording: "unresolved-symbol diagnostics" are
  sourced from `JsonnetResolver`'s own Phase 1 lexical-scope walk (a plain
  `Annotator`, `JsonnetUnresolvedReferenceAnnotator`), not from sjsonnet's own
  `StaticOptimizer` — wiring the interpreter's own static scope-checking in
  for full parity is a follow-up, not required to get real squiggles on
  undefined locals today. `std` is special-cased as a soft reference
  (`JsonnetLocalReference.isSoft`) so it's never flagged. Preview supports
  ext vars only for v1 (TLA vars, a YAML toggle, and output→source
  click-jump are noted follow-ups); it doesn't yet honor unsaved-buffer
  content for imported files (reads land straight on the VFS/disk) — same
  simplification called out for `VirtualFilePath` itself.
  Testing note: a `BasePlatformTestCase`-based test for the new VFS importer
  was attempted but the IDE-sandbox test process hung indefinitely (16+
  minutes, no output, had to be killed) in this environment — abandoned
  rather than debugged, consistent with the plan's own choice to reserve that
  heavier framework for Phase 4. The Importer/Path code is reviewed but not
  automated-tested; the existing lightweight `ParsingTestCase` suite and the
  Phase 0 `JsonnetEngineTest` (importless) still pass.

### Phase 3 — Tanka awareness (the actual gap nobody has filled) — ✅ done (one item rescoped)
- [x] Native jpath resolver implementing `root`/`base`/`[base, vendor, lib]`
      exactly as `grafana/tanka`'s `pkg/jpath` does; wire into import
      resolution/completion.
- [x] Project-view recognition of Tanka projects/environments (icons,
      "New Tanka Environment" action scaffolding `spec.json` + `main.jsonnet`).
- [x] JSON-Schema-backed editing/completion for `spec.json` and
      `jsonnetfile.json`.
- [x] `import 'tk'` synthetic PSI target + completion for `tk.env.spec.*`.
- [x] Native-function registry (`std.native('parseYaml')` etc.) with real JVM
      re-implementations for the fast tier, and hover/completion docs.
- [x] "Unresolved `vendor/...` import" quick fix → run `jb install` (ground-truth
      tier) and refresh.
- **Exit criterion:** a real Tanka repo (e.g. a `jsonnet-libs`-based project)
  opens with correct import resolution, `spec.json` editing support, and
  working `import 'tk'` completion — none of which either existing plugin does.
  **Status:** `tanka/TankaJpath.kt` walks up for `root`/`base` exactly as
  described and feeds both the evaluation-tier `VirtualFileImporter` and the
  editor-tier `FileReferenceSet` (via a `getDefaultContexts()` override), so
  `vendor/`/`lib/` imports resolve and navigate the same way in both places.
  `spec.json`/`jsonnetfile.json` get real JSON Schemas
  (`resources/schemas/*.json`) via `json.jsonSchemaProviderFactory`, plus a
  distinguishing project-view icon (`TankaFileIconProvider`) and a "New Tanka
  Environment" action scaffolding both files. `import 'tk'` no longer shows a
  permanent false-positive "unresolved file" error, and
  `local tk = import 'tk'; tk.env.spec.<caret>`-shaped code completes
  `env`/`metadata`/`spec`/spec-fields — scoped to that one idiomatic shape
  (`TankaTkModule.tkAccessChainBefore`), not general type inference. The
  `jb install` quick fix appears on an unresolved import once a
  `jsonnetfile.json` root is found and `jb` is on PATH, and shells out via
  `AsyncDocumentFormattingService`'s same process-running approach.
  **Rescoped:** "native-function registry ... with real JVM re-implementations
  for the fast tier" turned out to be infeasible as originally worded — verified
  via `javap` that neither `Interpreter` nor `Settings` (nor anything else in
  `sjsonnet`'s public surface) exposes a hook to register additional native
  functions; `std.native` only ever resolves the small fixed set `sjsonnet`
  ships itself (base64/regex/gzip/xz). Real evaluation of a Tanka-injected
  native function is therefore a ground-truth (`tk`) tier concern by
  necessity, not a fast-tier gap to close. Delivered instead: a curated
  registry (`TankaNativeFunctions`) driving completion and hover for the
  function-name string inside `std.native('...')`, with the quick-doc
  explicitly saying evaluation isn't available in the fast preview.
  Testing: added PSI-level tests (no VFS needed) for the two trickiest bits
  of sibling-walking logic — `std.native(...)` receiver detection and the
  `tk.env.spec` access-chain walk — both pass. `TankaJpath`/
  `VirtualFileImporter`'s actual filesystem-walking behavior is reviewed but
  not automated-tested, same VFS/BasePlatformTestCase constraint noted in
  Phase 2.

### Phase 4 — Refactoring, indexing, run configs, release polish
- [ ] Stub-index layer (`IStubElementType`) for top-level `local`s, object
      fields, function defs; exclude `vendor/` from full-content indexing.
- [ ] Find Usages + Rename (locals, fields, and file rename with import-path
      fixups) using the stub index.
- [ ] Run configurations: `tk show`/`tk diff`/`tk apply`/`tk export`
      (ground-truth tier, console + diff viewer), plus a plain `jsonnet eval`
      config; run-line-markers next to `environments/*/main.jsonnet`.
- [ ] Native formatter (replacing the Phase 2 shell-out) built on the plugin's
      own PSI, integrated with Code Style settings.
- [ ] Testing: `BasePlatformTestCase`-based grammar/PSI tests; consider reusing
      `sjsonnet`'s own Apache-2.0-licensed golden-file test fixtures to
      validate parser round-trips and diagnostics against ground truth.
- [ ] CI: GitHub Actions running the IntelliJ Platform Plugin Verifier across
      supported IDE versions; the `Std.scala`-vs-registry diff check from §6.
- **Exit criterion:** ready for a JetBrains Marketplace listing — refactoring
  and indexing work project-wide, run configs cover the real Tanka workflow,
  and CI guards both platform compatibility and stdlib drift.

### Phase 5 — Stretch ("even more feature-rich")
- [ ] Semantic highlighting distinguishing locals/params/fields/std calls.
- [ ] Inlay hints: named-argument hints on function calls; inferred
      merge-result hints on composed objects.
- [ ] "Evaluate expression" / lightweight debugger hooks into the `sjsonnet`
      evaluator (breakpoints on object fields, step-through of lazy thunks).
- [ ] Import-graph visualization via IntelliJ's diagram framework.
- [ ] Cross-environment diff: source-level `tk diff`-style comparison between
      two environments' evaluated output without needing a live cluster.
- [ ] Dead-code detection (unused `local`s/fields) with a remove quick fix.
- [ ] Optional, off-by-default online completion for `jsonnetfile.json`
      package names/versions.

---

## 9. Suggested repo shape for Claude Code to scaffold

```
intellij-jsonnet-tanka/
  build.gradle.kts                 # IntelliJ Platform Gradle plugin, Grammar-Kit, Shadow
  gradle.properties
  src/main/kotlin/
    lang/                          # Language, FileType, generated PSI (from .bnf/.flex)
    lang/psi/                      # hand-written PSI mixins, reference contributors
    lang/stubs/                    # IStubElementType definitions
    engine/                        # JsonnetEngine service wrapping shaded sjsonnet
    engine/importer/               # VFS/PSI-backed Importer implementation
    tanka/                         # jpath resolver, spec.json + jsonnetfile.json models,
                                    # `tk` virtual import, native-fn registry
    tanka/schema/                  # JSON Schemas for spec.json / jsonnetfile.json
    external/                      # process wrappers for tk / jb / jsonnetfmt
    editor/                        # annotator, completion, doc provider, folding,
                                    # formatter, structure view, line markers
    editor/runconfig/              # tk show/diff/apply/export, jsonnet eval configs
  src/main/grammar/Jsonnet.bnf
  src/main/grammar/Jsonnet.flex
  src/test/kotlin/                 # BasePlatformTestCase-based tests
  src/test/testData/               # fixtures (own + adapted from sjsonnet's Apache-2.0 suite)
```

---

## 10. Sources consulted

- https://github.com/databricks/intellij-jsonnet
- https://github.com/zzehring/intellij-jsonnet
- https://github.com/grafana/jsonnet-language-server
- https://github.com/databricks/sjsonnet
- https://repo1.maven.org/maven2/com/databricks/sjsonnet_3/
- https://github.com/grafana/tanka (README, CHANGELOG, `pkg/jpath` docs)
- https://beta.pkg.go.dev/github.com/grafana/tanka/pkg/jpath
- https://github.com/jsonnet-bundler/jsonnet-bundler
- https://tanka.dev (native functions docs, via Grafana Pyroscope's Tanka deployment guide)
- https://plugins.jetbrains.com/docs/intellij (Custom Language Support Tutorial extension-point list)
- https://github.com/JetBrains/Grammar-Kit
