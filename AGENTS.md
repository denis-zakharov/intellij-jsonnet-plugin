# AGENTS.md

Notes for picking this project back up in a fresh session. The full phased
plan and its per-phase status/decisions live in
`jsonnet-tanka-intellij-plugin-plan.md` — **read that file's checklists and
"Status" blocks first**, they're the primary record. This file exists for
things a fresh session would otherwise have to rediscover the hard way:
build-system gotchas, real bugs hit and fixed, and testing constraints
specific to *this* sandbox environment.

## What this is

A native (non-LSP) IntelliJ plugin for Jsonnet + Tanka: a Grammar-Kit PSI
grammar plus the embedded `sjsonnet` (Databricks, Scala 3, Apache-2.0)
evaluator running in-process — no external language server. Package
`com.dz.intellijjsonnet`, plugin id `com.dz.intellij-jsonnet-tanka`.

Phases 0–5 are implemented (4 and 5 partially — see the plan doc's Phase 4/5
status blocks for exactly what's deferred: `BasePlatformTestCase`-based
coverage for write-path code, which hangs/NPEs in this sandbox (see "Testing"
below); an import-graph diagram view; and online `jsonnetfile.json`
completion, which turned out not to be a well-defined feature for this
ecosystem at all — see the plan doc).

## Build system

- **Use `./gradlew`**, not a system `gradle` — the wrapper is checked in
  (`gradle/wrapper/`, Gradle 9.3.0). No hardcoded JDK *path* anywhere; both
  `build.gradle.kts` and `shaded-sjsonnet/build.gradle.kts` declare a
  `java { toolchain { languageVersion = 21 } }` / `kotlin { jvmToolchain(21) }`
  block, so compilation always targets JDK 21 regardless of which JDK
  actually runs Gradle. **Both modules need the toolchain block** — omitting
  it on `shaded-sjsonnet` causes a "compatible with JVM runtime version 25,
  but ... only compatible with 21" resolution failure, because Gradle
  attaches a JVM-version attribute to consumable configurations even for a
  module with no real sources.
- **The Gradle *daemon* itself is pinned to JDK 21** via a checked-in
  `gradle/gradle-daemon-jvm.properties` (Gradle's "daemon toolchain" feature,
  `./gradlew updateDaemonJvm --jvm-version=21` — needs
  `org.gradle.toolchains.foojay-resolver-convention` **version `1.0.0`**
  applied in `settings.gradle.kts`'s top-level `plugins {}` block; older `0.x`
  versions fail against this Gradle version's `JvmVendorSpec` API). This is a
  toolchain *version number*, not a path, so it stays portable — but it does
  mean the daemon no longer just inherits whatever JVM is on `PATH`. This
  exists because the Kotlin Gradle Plugin here (2.0.21, pre-dates JDK 25) forks
  its Compile Daemon from the Gradle daemon's own JVM, and its vendored
  `JavaVersion` parser throws on any JDK-25-family version string — see
  AGENTS.md's Testing section for the full symptom/diagnosis if this
  resurfaces (e.g. after a Kotlin/Gradle plugin upgrade removes the need for
  the pin, or a new JDK major version needs the same treatment).
- Two modules: root plugin + `:shaded-sjsonnet` (shades `sjsonnet` + its Scala
  3 runtime into `com.dz.intellijjsonnet.shaded.*` so it can't collide with
  JetBrains' own bundled Scala plugin). The shaded dependency lives in a
  dedicated `shaded` configuration, NOT `implementation` — otherwise its raw
  transitive jars (scala-library, snakeyaml, ...) leak into the plugin's
  `lib/` alongside the fat jar instead of being replaced by it.
- Grammar-Kit codegen has a real bootstrapping order problem: Kotlin compiles
  *before* Java in a mixed source set, so a `psiImplUtilClass` +
  `methods=[...]` mixin (which needs the util class already compiled to
  detect method signatures at generation time) can't work in one Gradle
  invocation. **Solution used throughout this codebase: don't use
  `methods=[...]`/`psiImplUtilClass` at all.** For simple accessors, add
  plain Kotlin extension properties over the generated interfaces
  (`lang/psi/JsonnetPsiExtensions.kt`). For real behavior (references,
  rename), use `mixin="some.FooMixin"` (no `methods=`) and implement the
  interface directly in the Kotlin mixin class — the generated `Impl` class
  extends the mixin, so the concrete override is visible at runtime via
  `instanceof`/`is` checks even though the plain generated interface doesn't
  declare it. Both a member on the mixin AND an extension property with the
  same name can coexist without conflict (member wins when the static type is
  the mixin/impl; the extension resolves when code refers to the plain
  generated interface type) — see the comment at the top of
  `JsonnetPsiExtensions.kt`.
- **Grammar-Kit stub-index recipe** (used for `bind`/`field` in
  `lang/stubs/`, confirmed by decompiling the installed
  `~/.gradle/caches/.../grammar-kit-2023.3.4.jar` — `javap -v` on
  `JavaParserGenerator.class`'s constant pool — rather than guessing, since
  there's no Grammar-Kit doc site bundled and web access isn't reliably
  available): add `stubClass="fully.qualified.XStub"` to the rule in the
  `.bnf` (keep the existing `mixin=` too, if any) and set a **root** (not
  per-rule) `elementTypeFactory="fully.qualified.Factory.method"` attribute —
  that factory is consulted for the `IElementType` constant of *every* rule in
  the generated `Types` interface, not just the stubbed ones, so it must
  special-case the stubbed rule names and fall back to the plain
  `elementTypeClass` type for everything else. This flips the generated `Impl`
  class to *also* get a second constructor `(XStub stub, IStubElementType
  stubType)` calling `super(stub, stubType)` — note: raw `IStubElementType`,
  no generics — so any mixin for that rule must extend
  `StubBasedPsiElementBase<XStub>` with a matching two-constructor shape
  (`IStubElementType<*, *>` in Kotlin is fine; star-projection erases to the
  same raw type). The generated PSI interface also automatically starts
  extending `StubBasedPsiElement<XStub>` — no `implements=` needed. Also swap
  `ParserDefinition.getFileNodeType()`'s plain `IFileElementType` for a custom
  `IStubFileElementType` subclass (override `getStubVersion()`/
  `getExternalId()`; no need to override `getBuilder()` — the default
  `DefaultStubBuilder` walks the tree and calls `createStub` on anything whose
  element type is `instanceof IStubElementType`), and register
  `<stubElementTypeHolder class="...Types"/>` in `plugin.xml` so the class
  holding the `IElementType` constants gets force-loaded early (otherwise
  deserializing a persisted stub tree via `getExternalId()` on a cold IDE
  start could race against those constants never having been touched yet).
  `StubIndexExtension`s need their own `<stubIndex
  implementation="..."/>` entries — **no `key` attribute** (the key comes from
  `getKey()`; an extra attribute makes the platform try to deserialize it into
  the index class and log `No accessors for ...Index` on every start). This whole recipe compiled and passed on
  the **first** `./gradlew generateParser` + `compileKotlin` attempt following
  it — worth trusting once confirmed via decompilation rather than
  trial-and-error guessing.
- `verifyPluginStructure` / `verifyPluginProjectConfiguration` are cheap,
  fast sanity checks on `plugin.xml` and project config — run them after
  touching `plugin.xml`. The full `verifyPlugin` (binary Plugin Verifier
  against real IDE builds) is much heavier; CI runs it, local iteration
  shouldn't unless specifically debugging a compat issue.

## Scala/sjsonnet interop gotchas (real bugs hit, not guesses)

- **Shading corrupts string literals, not just class names.** Shadow's
  relocator rewrites string constants that look like the relocated package
  path, not just bytecode class references. Relocating the bare `os` package
  (for os-lib) turned the string literal `"os.arch"` inside `lz4-java`'s
  `System.getProperty("os.arch")` call into
  `"com.dz.intellijjsonnet.shaded.os.arch"`, silently returning `null` and
  causing an `NPE` deep in a static initializer. Fix: only relocate package
  prefixes actually at risk of colliding with IntelliJ's own bundled classes
  (`scala`, `sjsonnet`, `fastparse`, `ujson`, `upickle`, `geny`, `pprint`,
  `mainargs`, `scalatags`, `org.yaml.snakeyaml`) — leave generic-sounding
  third-party packages like `os` unrelocated. See the comment in
  `shaded-sjsonnet/build.gradle.kts`.
- **The native-function hook is `StdLibModule`, not `Interpreter`.** This
  file used to say sjsonnet has no way to register `std.native(...)` functions;
  that was checked against `Interpreter`/`Settings` only. `Interpreter`'s 9th
  constructor argument is the `std` object, and
  `sjsonnet.stdlib.StdLibModule(nativeFunctions, additionalStdFunctions).module()`
  builds one — both maps are `Map[String, Val.Func]`, subclass
  `Val.Builtin1/2/3` from Kotlin. **Every `Interpreter` the plugin builds must
  pass `SjsonnetExtensions.std`** (`engine/extension/`) instead of
  `Interpreter.$lessinit$greater$default$9()`: `JsonnetEngine`,
  `SjsonnetStaticCheck`, `StdLibRegistry` do. It provides `std.id`, go-jsonnet's
  parameter names for 14 functions, and Tanka's 8 pure natives
  (`TankaNatives`); `helmTemplate`/`kustomizeBuild` stay ground-truth-tier.
  Quirks found the hard way: additional std functions override built-ins, but
  `native` is appended *after* the merge so it can't be replaced; ujson's
  `Null` is an object (match `` `Null$`.`MODULE$` ``, `is Null` never matches);
  `Error.fail` returns Scala's `Nothing$`, not Kotlin's `Nothing`.
  Ground truth for the natives is `tk eval` in any directory with a
  `jsonnetfile.json` — `TankaNativesTest` pins its verbatim output, so extend
  it the same way (run `tk` first, then paste). `docs/sjsonnet-gaps.md` is the
  full sjsonnet-vs-go-jsonnet comparison (what's closed, what isn't and why);
  `scripts/sjsonnet-conformance.py` regenerates it after a version bump.
- **`std`'s own functions are hidden fields.** `Val$Obj.visibleKeyNames()`
  returns *empty* for the default std object — std's own 170-ish functions
  are internally `::`-hidden, matching how real Jsonnet's `std.jsonnet`
  defines them. Use `allKeyNames()` instead (filter `__`-prefixed internals).
  Getting this wrong is exactly the kind of bug that fails silently: stdlib
  completion/hover just returns nothing, no error, no exception — it was
  broken this way from Phase 2 through most of Phase 4 before a sanity test
  (`StdLibRegistrySanityTest`) caught it. **Lesson: for any registry sourced
  from a runtime reflection call like this, write a test asserting it's
  actually non-empty, not just that it doesn't throw.**
- **The raw default std object doesn't fully enumerate either way.** Even
  `Interpreter.$lessinit$greater$default$9()` (the "give me the default std"
  static accessor) needs to be run *through* an actual `Interpreter.evaluate`
  call before `allKeyNames()`/`visibleKeyNames()` return anything populated —
  `StdLibRegistry.computeMemberNames()` evaluates the literal expression
  `"std"` and reads the keys off the resulting `Val.Obj`, rather than reading
  the constructor-default argument directly.
- **Constructing the interpreter from Kotlin**: see `engine/JsonnetEngine.kt`
  for the full working pattern — Scala `Option`/`Map` construction
  (`` `Option$`.`MODULE$`.apply(...) ``, `` `Map$`.`MODULE$`.empty() `` +
  `.updated(k, v)`), and pulling constructor defaults via backtick-escaped
  static methods (`` Interpreter.`$lessinit$greater$default$N`() ``). Copy
  this pattern rather than re-deriving it.
- **Unresolved-variable detection happens in `CachedResolver.parse()`, not in
  a later `StaticOptimizer.optimize()` call** — TODO.md item 3's spike
  (`SjsonnetStaticCheck.kt`) confirmed this the hard way: `StaticOptimizer`'s
  class file does contain the string `"Unknown variable: \u0001"` (found via
  `javap -v` grepping the constant pool for `variable`), which is what
  originally suggested `optimize()` was the right call to wrap in a
  try/catch — but calling it on a tree with a genuinely-undefined name
  **doesn't throw**; `parse()` itself already returns `Left(a sjsonnet.Error)`
  before `optimize()` is ever reached, because slot-index resolution
  (`Expr.Id` → `Expr.ValidId`) has to happen eagerly at parse time (the
  interpreter's variable environment is slot/array-indexed, not name-keyed,
  so an index has to exist for every reference, unresolved or not — laziness
  only defers *evaluation*, not this). Confirmed by writing the *use* case
  first (`SjsonnetStaticCheck.kt`'s own unit tests) rather than trusting the
  string-search alone — two of the seven tests failed on the first run
  (`optimize()` never threw), which is what surfaced this. **Lesson, same
  shape as the `StdLibRegistrySanityTest`/resolver-bug lessons above: a
  promising string found via `javap` names *where a message lives*, not
  necessarily *when it fires* — write the actual call and a failing-case
  test before trusting the theory.** `parse()`'s `Left` also carries real
  `ParseError`s (genuine syntax errors) through the exact same channel —
  distinguish by type (`error is ParseError` → skip, that's not what you
  want) or you'll double-report syntax errors the PSI parser already flags.
- **`Interpreter` already exposes everything needed to run sjsonnet's real
  static analysis standalone** — `resolver()`, `evaluator()`,
  `createOptimizer(EvalScope, Val$Obj, HashMap, HashMap)`,
  `internedStrings()`, `internedStaticFieldSets()` are all public. No need to
  hand-assemble an `EvalScope`/`std` from scratch; just build an `Interpreter`
  the same way `JsonnetEngine` already does (with `Importer.empty()` — import
  boundaries don't leak names into a file's lexical scope in Jsonnet, so
  variable-resolution checking needs no real importer) and pull these off it.
  `sjsonnet.Error` (base of `StaticError`/`ParseError`) `extends
  java.lang.Exception` and carries `.stack(): List<Error$Frame>`, each frame
  exposing `.pos(): Position` → `.offset(): Int`, a plain character offset
  into the source string — maps directly onto a PSI `TextRange` with no
  line/column math, since both sjsonnet and our own parser consume the exact
  same source string.
- **`StaticOptimizer.optimize()` is still fail-fast even though it turned out
  not to be the trigger point here**: `StaticError.fail(...)` is a Scala
  `Nothing`-returning throw, so any single parse-or-optimize pass reports at
  most one unresolved name, never all of them. This is why
  `JsonnetUnresolvedReferenceAnnotator` still uses the hand-rolled
  `JsonnetResolver` PSI walk as its *primary*, complete source of "which
  identifiers are unresolved," with `SjsonnetStaticCheck`'s real-sjsonnet
  result (cached per file via `CachedValuesManager` — it reparses the whole
  file, too expensive to redo per identifier) layered on top only to catch
  cases the hand-rolled walk's own logic might miss.

## Evaluation / Preview gotchas (TODO.md items 4–5)

- **`Interpreter`'s Map-based constructor makes every ext/TLA var `ext-code`**
  (`ExternalVariable.code`, seen in bytecode). A plain string must be passed as
  a Jsonnet string literal — `JsonnetEngine.toScalaMap` does it via ujson.
- **Field *key* positions aren't recoverable from an evaluated `Val.Obj`.**
  sjsonnet 0.7.4 folds constant-field objects into `ConstMember(v)` (value only,
  and `{a:1}+{a:2}` merges into one such object); `_sourceMemberList` (real
  `Expr.Member.Field` positions) is only set for non-constant objects. Only
  `Val.pos` (where the value was produced) is consistent — `SourceLocator` uses
  that. Found by a reflection probe (a throwaway `Probe.java` against
  `shaded-sjsonnet/build/libs/shaded-sjsonnet.jar`; in Java the shaded nested
  Scala classes only resolve as `Val.Obj`, not `Val$Obj$Member`); worth
  repeating that probe rather than trusting `javap` field lists when asking
  "what does sjsonnet keep at runtime".
- **`YamlRenderer` defaults to quoted keys and no trailing newline**; pass
  `quoteKeys = false` for `tk show`-style output.
- **Evaluation reads text via `VirtualFileText`** (live `Document` first). Keep
  it that way: `SourceLocator` offsets and error positions are only meaningful
  if they index the exact string sjsonnet parsed.
- **Navigating from Preview changes the editor selection**, which Preview itself
  listens to; `JsonnetPreviewPanel.jumpTarget` swallows that one event. The
  test `preview jump into an imported file does not retarget the preview` fails
  without the guard (verified by mutation).
- macOS: `sed -i` needs `sed -i ''` — the bare form eats the script as the
  backup suffix and errors with "bad flag in substitute command".

## Navigation / completion (member resolution)

- **Member access is resolved by `lang/psi/reference/JsonnetStaticValues.kt`**, a PSI-only
  best-effort evaluator (value = object literals + callable bodies), not a type system. The Expr PSI
  is *flat* (`a.b(c) + d.e` is one `Expr` with children `a`, `.b`, `(c)`, `+`, `d`, `.e`), so the
  receiver of a suffix is "its operand's atom + suffixes up to it" — `split()` builds operands.
  Empty means *unknown*, never "no such member". `JsonnetFieldReference` (resolve + completion
  variants) and `JsonnetLocalReference.getVariants` are the only consumers; completion is
  `getVariants()` on the references, plus `JsonnetKeywordCompletionContributor` for keywords/`std`.
- `$` is the *outermost enclosing* object literal (`JsonnetResolver.outermostObjectLiteral`), not
  the file's root object. `JsonnetFieldReference` is poly-variant: `(a + b).x` resolves to both, so
  renaming one over-approximates onto the other's uses — accepted.
- **Import path references used to be dead.** They were registered as a `PsiReferenceContributor`
  on the `STRING` *leaf*, and a bare leaf never asks reference contributors for references —
  `findReferenceAt` returned null, so go-to-definition and path completion never worked and nothing
  tested it. Fix: `JsonnetImportExpr` (mixin) hosts the `FileReferenceSet` (`getReferences()`),
  with a `JsonnetImportExprManipulator` so file rename/move can rewrite the path. **Lesson: a
  reference contributor only fires on elements whose `getReferences()` delegates to the registry —
  never on plain token leaves. Test any new reference with `findReferenceAt` at the caret.**
- `ResolveCache` isn't available in `ParsingTestCase`'s mock project; `JsonnetFieldReference`
  falls back to uncached resolution when `ResolveCache.getInstance` is null.
- **Unused hidden fields are judged project-wide** (`inspection/JsonnetFieldUsageSearch.kt`), not
  just inside the field's own object — in Tanka libraries `::` fields *are* the API, and the
  in-object-only check flagged them everywhere. `JsonnetUnusedDeclarationUtil.isUnusedHiddenField`
  stays the cheap, service-free in-object check; the inspection then asks the word index for same-named
  tokens. Rule: `x.name` counts unless it positively resolves to a *different* field (unknown receiver
  ⇒ maybe used); a `'name'` string counts (`o['name']`, `std.objectHasAll`); comments/locals don't.
  **Mixin fields are exempt from the "resolves to a different field" clause**
  (`JsonnetFieldUsageSearch.isMixin`: `+::`/`+:::`, or inside the value of a `+:`-family field): in
  `new():: { props:: {}, out: self.props }` + `withProps():: { props+:: {..} }`, `self.props` resolves to
  the base only, but at runtime reads the merge. For them any same-named read, or a same-named
  declaration elsewhere (`c+:: { logging:: null }` exists for its visibility, never read), counts.
  Found on a real Tanka project (`with*` mixins were all reported). Unreferenced exported API
  (`withX():: ...` never called in the project) is still reported, by design;
  `vendor/` files aren't inspected. The word index needs `JsonnetFindUsagesProvider`'s
  `WordsScanner` — without a registered provider there is nothing to search (and no Find Usages).

## Platform baseline

- Built against IntelliJ IDEA Community **2025.1** (`platformVersion=2025.1`, `pluginSinceBuild=251`).
  From 2025.1 the JSON plugin's classes (`com.jetbrains.jsonSchema`) are no longer on the plain
  platform classpath: `build.gradle.kts` declares `bundledPlugin("com.intellij.modules.json")`
  (that *is* the plugin's id). Newer platforms bundle a newer Kotlin stdlib/metadata than KGP 2.0.21
  can read (it tolerates one minor ahead, i.e. 2.1) — expect to bump Kotlin when moving to 2025.2+.

## Import graph / marketplace notes (TODO.md items 7, 9)

- **Import resolution has one implementation: `TankaJpath.resolveImport`**, used
  by both `VirtualFileImporter` (evaluation) and `JsonnetImportGraph` (the
  Imports tool window). Change it there, not in either caller. It may return a
  directory; the graph treats that as unresolved, the importer's `read` rejects it.
- **`FileTypeIndex.getFiles` has no defined order** — it made a "Imported By"
  test flaky (passed, then failed on a swapped pair). Anything that turns index
  results into user-visible order or into a test assertion must sort them
  (`JsonnetImportGraph` sorts by path).
- **Light fixtures don't instantiate tool windows**, so
  `ToolWindowManager.getToolWindow(id)` is null in `BasePlatformTestCase`; assert
  registration via `ToolWindowEP.EP_NAME.extensionList` instead. Panels are
  tested by constructing them directly and calling an `internal` synchronous
  `refreshNow()`-style seam (the real path is async `ReadAction.nonBlocking`).
- **Don't add `<depends>com.intellij.java</depends>`** (or `bundledPlugin` for it)
  back: no code uses Java PSI, and it restricts installs to Java-capable IDEs,
  excluding GoLand. It was a Phase 0 leftover.
- `THIRD_PARTY_NOTICES.md` lists licenses copied from the resolved artifacts' POMs;
  regenerate it (`./gradlew :shaded-sjsonnet:dependencies --configuration shaded`,
  then read each POM's `<license>`) whenever the sjsonnet version changes. It also
  reproduces each license's full text and copyright line (MIT/BSD/Apache require
  that in a binary distribution), so re-check those against upstream too — a POM
  license can change between versions (xz went from Public Domain to 0BSD in 1.11).

## Startup warnings (checked via the test sandbox log)

- Light tests load `plugin.xml` for real, so plugin-registration warnings show up in
  `.intellijPlatform/sandbox/*/IC-*/log-test/idea.log` without launching `runIde`:
  truncate it, run `./gradlew cleanTest test`, then
  `grep -h "WARN\|ERROR" idea.log | sort | uniq -c`. That's how the two fixed below
  were verified (448 and 33 occurrences → 0).
- Two `FileType`s sharing one `Language` must override `getDisplayName()` *and*
  `getDescription()` (`FileTypeManagerImpl.checkUnique`).
- **Not ours, ignore:** `kotlin.mpp.tests.force.gradle` conflicting registry key
  (bundled Kotlin plugin vs itself), `No URL bundle (CFBundleURLTypes)`,
  `Bundled shared index is not found`, empty custom trusted root certificates —
  all `runIde`/sandbox-environment noise.

## Grammar bugs hit and fixed (Jsonnet.bnf / Jsonnet.flex)

The first three were caught by the `ParsingTestCase`-based tests in
`JsonnetParsingTest.kt` (a "kitchen sink" test plus targeted regression
cases) — **write a parser test for any new grammar construct**, this class of
bug is otherwise silent (parses "successfully" with a wrong PSI shape, or
throws a confusing error deep inside an unrelated construct).

1. A `pin=N` on a Grammar-Kit rule commits the parser to that alternative once
   element N has matched, even if a *later* element in the same alternative
   is what actually distinguishes it from a different valid parse. Concretely:
   `objectComprehension`'s `pin` was on `computedFieldName` (element 2 of
   `objectLocal* computedFieldName COLON expr forSpec compSpec*`), but only
   `forSpec` (element 5) actually distinguishes a comprehension from a plain
   `[computed]: value` field — pinning at element 2 broke backtracking into
   `objectMemberList` for the (far more common) non-comprehension case. Fix:
   move `pin` to right after the element that's actually diagnostic.
2. An ordered choice (`a | b`) never backtracks once the first alternative
   *succeeds*, even if it only consumed a short prefix and a longer/different
   parse was intended. `sliceContent ::= expr | sliceParts` let `expr` win on
   `nums[1:3]` (matching just `1`), so `sliceParts` (needed for the `:3` part)
   was never tried. Fix: don't split into competing alternatives when they
   share a prefix — unify into one rule where the *presence* of a token (the
   colon) is what varies, not the choice of two whole sub-rules.
3. JFlex's plain longest-match can't correctly prefer a short specific pattern
   (the closing `|||` of a text block) over a longer generic one (a
   "greedily consume the rest of the line" rule) when trailing content (a
   real-world `|||,`) follows the short pattern on the same line — the
   generic rule's match is always literally longer. Fix: use JFlex's `~`
   ("match up to") operator (the same tool used for `/* */` comments) to
   match the *entire* text block (open through close) as one rule, instead of
   trying to detect the closer as a separate competing alternative in a
   custom lexer state.
4. **`postfixSuffix` never supported real Jsonnet's `expr { ... }` "object
   mixin juxtaposition" sugar** (`expr { ... }` ≡ `expr + { ... }`, no
   operator token at all between them) — idiomatic and extremely common in
   real Tanka/k8s-libsonnet code
   (`deployment.new() { spec+: {...} }`-shaped patterns), just missing from
   the grammar entirely (`postfixSuffix ::= dotSuffix | indexSuffix |
   callSuffix`, no fourth alternative). Unlike bugs 1–3, this one was found
   not by a hand-written `ParsingTestCase` fixture but by literally running
   the parser over a real, large, external corpus — a sparse clone of
   `jsonnet-libs/k8s-libsonnet`'s `1.34/` directory (688 real files) — as
   part of TODO.md item 2's stub-index scale validation. Every other file
   parsed cleanly; this construct was the one real gap. Fix: add
   `objectLiteral` as a `postfixSuffix` alternative (matches the real
   spec grammar's `expr3 objinside` production) — no ambiguity with
   `objectLiteral` already being a valid `atom`, since `postfixExpr ::= atom
   postfixSuffix*` already left-recursion-eliminates the same way `.`/`[]`/
   `()` suffixes do. Confirmed via a rerun against the same corpus: 0/688
   parse errors afterward. Three regression tests added to
   `JsonnetParsingTest.kt`. **Lesson: a hand-written "kitchen sink" test
   fixture, however thorough it feels, only covers constructs someone
   thought to write — running the real parser over a large real-world
   corpus (any `jsonnet-libs` package works; see TODO.md item 2 for the
   exact sparse-clone recipe: `git clone --depth 1 --filter=blob:none
   --sparse <repo> && git sparse-checkout set <one-version-dir>`) is worth
   doing again whenever the grammar changes substantially, not just once.**

## Resolver bugs hit and fixed (lang/psi/reference/JsonnetResolver.kt)

- **`resolveLocalName`'s object-local branches returned the wrong node.**
  Found while building Phase 5's dead-code inspection, not by inspection of
  the code itself — a `JsonnetUnusedDeclarationUtilTest` case ("object-scoped
  local used by a field value") failed because `reference.resolve()` on a
  bare-identifier usage of an *object-scoped* `local` (`{ local secret = 1,
  x: secret }`, as opposed to an expression-level `local secret = 1; ...`)
  returned the wrapping `JsonnetObjectLocal` PSI node (`local secret`) instead
  of the actual `JsonnetBind` (`secret`) — both the `is JsonnetObjectLiteral`
  and `is JsonnetObjectComprehension` branches did `.firstOrNull { it.bind
  ... }?.let { return it }`, returning `it` (the objectLocal) instead of
  `it.bind`. Present since Phase 1; nothing had ever compared a resolved
  object-local target against the actual bind node before, so go-to-
  definition still "worked" (jumped to a plausible-looking line) and nothing
  threw. **Lesson, matching the `StdLibRegistrySanityTest` one from Phase
  4:** a `resolve()`-based check is worth a test asserting *which exact node*
  it returns, not just that navigation "looks right" or doesn't throw — this
  class of bug is silent by construction.

## Testing: what works here and what doesn't

- **`ParsingTestCase`** (JUnit 3-style, `com.intellij.testFramework`) is the
  workhorse — fast, no IDE sandbox, real parser/PSI/reference behavior. Used
  in `JsonnetParsingTest.kt` and `JsonnetLexerTest.kt`. It provides a real
  `MockApplication`/`MockProject` but a **minimal** one: no `PsiFileFactory`,
  no `CodeStyleManager`, no `VirtualFileManager`/`LocalFileSystem`, no
  `CommandProcessor`. Anything needing those throws a clean NPE naming the
  missing service — that's a hard signal to stop and mark the code
  reviewed-but-untested rather than fight the fixture.
- **`BasePlatformTestCase` now works fine in this environment** — the earlier
  "hangs indefinitely (16+ min)" report was wrong about the cause. Root-caused
  in a later session (see `JsonnetPlatformIntegrationTest.kt`, which covers
  exactly the write paths this used to say were untestable): it's not the
  fixture, it's two independent, real bugs the fixture's strict
  `TestLoggerFactory` (which turns a logged `error()` into a hard test
  failure) exposed that plain code review never would have:
  1. `JsonnetFileType.kt`'s `LibsonnetFileType` had `getName()` hardcoded to
     `"Jsonnet"` (copy-paste from `JsonnetFileType`) instead of `"Libsonnet"`,
     which `plugin.xml`'s `<fileType name="Libsonnet">` declares. The
     platform's `FileTypeManagerImpl` asserts these match at startup; the
     mismatch throws deep inside `StubIndexImpl`'s async initialization,
     which **wedges a `CompletableFuture` that `BasePlatformTestCase
     .tearDown()`'s leak-check (`waitUntilStubIndexedInitialized`) awaits with
     no timeout** — that's the actual 16-minute "hang." A `jstack` dump of the
     stuck `Test worker` thread during a repro (see git history around the
     TODO.md item 1 fix for the exact stack) pointed straight at it. Once
     fixed, the same test suite completes in ~3 seconds.
  2. Separately, `PsiReferenceBase.getRangeInElement()`'s default impl throws
     `PluginException: No ElementManipulator instance registered for
     JsonnetNameRefImpl`/`JsonnetDotSuffixImpl` — but *only* from text-based
     reference search (rename's "other usages" pass, Find Usages), not plain
     `resolve()`, which is exactly the kind of thing a `ParsingTestCase`-only
     test suite can never exercise. Fixed by overriding `getRangeInElement()`
     directly in `JsonnetLocalReference`/`JsonnetFieldReference` instead of
     registering an `ElementManipulator`.
  If `BasePlatformTestCase` ever appears to hang again, don't assume it's the
  fixture — grab a `jstack` dump of the `GradleWorkerMain`/`Test worker`
  thread first (see the diagnostic-script pattern used to root-cause this,
  worth reconstructing from git history if needed) before spending time on
  workarounds. It's much more likely to be a real bug like these two.
- **Writing the very first `BasePlatformTestCase` test in this repo also hit
  an unrelated, environment-level build bug**, separate from the above and
  worth knowing about if `./gradlew compileTestKotlin`/`test` suddenly starts
  throwing `IllegalArgumentException: 25.0.4` from deep inside
  `kotlin-compiler-embeddable`'s vendored `JavaVersion.parse`: the Kotlin
  Compile Daemon is forked from whatever JVM launched the **Gradle daemon**
  (not from the per-module toolchain JDK — that only controls `-jdk-home`,
  i.e. the *target* JDK for compiled output, not which JVM actually *runs*
  the daemon process), and this repo's Kotlin Gradle Plugin version (2.0.21,
  from before JDK 25 existed) can't parse a JDK-25-family version string at
  all — it throws regardless of the exact patch number. Since AGENTS.md's
  build-system section deliberately allows the Gradle daemon to run on
  whatever JDK is on `PATH` (may be JDK 25 on a given dev machine), this can
  resurface on a fresh machine/JDK upgrade. **Fixed portably, without
  hardcoding any JDK path**, via Gradle's own "daemon toolchain" feature: a
  checked-in `gradle/gradle-daemon-jvm.properties` (generated via `./gradlew
  updateDaemonJvm --jvm-version=21`, which needs the
  `org.gradle.toolchains.foojay-resolver-convention` plugin — **use version
  `1.0.0`**, not the `0.x` you'd naively guess first; older versions reference
  a `JvmVendorSpec.IBM_SEMERU` field this Gradle version removed and fail with
  a confusing `Invalid task configuration` error) pins the *Gradle daemon
  itself* to JDK 21 by feature version, resolved against locally installed
  toolchains — no absolute path anywhere. If this regresses, verify with
  `./gradlew someTask --info | grep "Starting process 'Gradle build daemon'"`
  that the daemon launches on a JDK 21 binary, not whatever's on `PATH`.
- Consequence of all the above: `VirtualFilePath`/`VirtualFileImporter`
  (Phase 2), rename (`setName`/`handleElementRename`), the native formatter's
  `CodeStyleManager.reformat()` invocation, the stub-index build/query
  pipeline, and `JsonnetUnusedDeclarationInspection`'s quick-fix wiring are
  now **covered by `JsonnetPlatformIntegrationTest.kt`** (11 tests), which
  also caught three more real, previously-invisible bugs while being written
  — worth knowing about since they're the kind of thing that could easily
  recur in similar code elsewhere:
  - `JsonnetResolver.resolveLocalName` had no `is JsonnetBind` case, so a
    function-sugar bind's own parameters (`local f(x) = x + 1;` — the single
    most common Jsonnet idiom) **never resolved inside the function body at
    all**. Go-to-definition, rename, and (very likely) the unresolved-
    reference annotator were all silently broken for this case since Phase 1.
    Fixed by adding the missing branch (mirrors the existing
    `JsonnetFunctionExpr`/`JsonnetField` handling).
  - `JsonnetStubIndexUtil.isTopLevelExpr`'s `JsonnetBind`/`JsonnetField`
    branches didn't check `paramList == null` before recursing, so anything
    nested inside a function-sugar body (`local f(x) = local y = ...; y;`)
    was incorrectly stub-indexed as if top-level — directly contradicting the
    class's own documented intent ("excludes anything nested inside a
    function body"). Fixed by adding the `paramList == null` guard.
  - `JsonnetBlock.getIndent()` only special-cased `RBRACE`/`RBRACK` (the
    *closing* bracket), not `LBRACE`/`LBRACK` — so every opening brace got an
    extra, unwanted indent level relative to its own container, which (via
    how the formatting engine anchors subsequent sibling lines) pushed the
    *entire* reformatted block one level too deep, and doubled up further for
    genuinely nested content via a second, independent bug (see the next
    point). Fixed by including `LBRACE`/`LBRACK` in the `NoneIndent` case.
  - `JsonnetBlock.getIndent()` also double-indented every object/array
    member: `objectLiteral`/`arrayLiteral` wrap a *separate*
    `objectMemberList`/`arrayMemberList` node (see `Jsonnet.bnf`), and both
    that wrapper and its own children were in `INDENTED_CONTAINERS`, so a
    field inherited indent from both its literal grandparent and its
    member-list parent. Fixed by making the member-list wrapper types
    "transparent" (`Indent.getNoneIndent()` for themselves, deferring the
    single indent level to their children).
  - `JsonnetPsiListEditUtil.deleteListMember` deleted the member and its
    adjacent comma as two separate `PsiElement.delete()` calls, leaving the
    whitespace *between* them (a separate sibling node) behind — e.g.
    removing an unused local from `local used = 1, unused = 2; used` left
    `local used = 1 ; used` (stray space before `;`). Worse, chaining two
    `.delete()` calls could throw `PsiInvalidElementAccessException` on the
    second one once other fixes changed the tree shape enough to trigger
    rebalancing. Fixed with `ASTDelegatePsiElement.deleteChildRange`, the
    standard IntelliJ idiom for deleting a contiguous run of sibling AST
    nodes atomically.
  None of these five were hypothetical — each had a concrete, minimal
  failing input surfaced by an actual test run, not by inspection. Lesson,
  extending the one from the Resolver section above: **write the
  `BasePlatformTestCase` test *first*, expect it to fail for a real reason on
  the first run, and read the actual diff/exception rather than assuming a
  fixture problem** — every one of these five bugs looked, at first glance,
  like it could plausibly be a test-authoring mistake, and every one turned
  out not to be.
- Their *read-side* logic (pure PSI navigation and reference resolution with
  no service dependency — e.g. `TankaNativeFunctions` receiver detection,
  `TankaTkModule` access-chain walking, `PsiNameIdentifierOwner.getName()`,
  `JsonnetUnusedDeclarationUtil`'s detection logic, `JsonnetStubIndexUtil`'s
  top-level/vendor logic, the inlay-hints provider's param resolution) is
  additionally unit-tested wherever it could be split out from the
  service-dependent wiring around it — that split is still the reusable
  pattern here: keep anything `PsiFileFactory`/`CodeStyleManager`/VFS-shaped
  as thin as possible, put the actual logic in a plain object next to it, and
  test that object directly at both levels (fast unit test for the logic,
  `BasePlatformTestCase` test for the end-to-end wiring).

## Sandbox/environment quirks (not project-specific, but bit this session)

- The bash command-safety classifier can go through a temporary outage where
  *every* non-trivial command (anything but the most obviously side-effect-free
  reads) fails with "temporarily unavailable (overloaded)" — including
  `Monitor` and even `sleep`. Simple read/list commands still work during
  this. There's no way to wait it out efficiently (can't background-poll
  without the classifier itself); just retry the actual command periodically
  and use the downtime for pure file-editing/review work.
- Runtime IDE sandbox jars (under
  `~/.gradle/caches/*/transforms/*/transformed/ideaIC-*`) are **not** a
  reliable place to `javap`/`unzip -l` search for platform API classes to
  guess their package — many core classes (`FormattingModelBuilder`,
  `UnresolvedReferenceQuickFixProvider`, etc.) simply aren't present as
  listable zip entries there even though they resolve fine on the real Gradle
  *compile* classpath. When unsure of an exact package/class name, it's
  faster to just write the plausible import and let the Kotlin compiler
  error guide the fix than to spend time on jar archaeology in the wrong
  place. (Decompiling a *plugin* jar like the bundled Kotlin plugin, which
  *does* reference the class you're after, to read its actual import — via
  `javap -c` on a `.class` that uses the type — is a more reliable trick than
  searching for the defining jar directly.)
