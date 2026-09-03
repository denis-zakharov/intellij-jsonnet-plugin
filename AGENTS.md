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

Phases 0–4 are implemented (4 partially — see the plan doc's Phase 4 status
block for exactly what's deferred: `BasePlatformTestCase`-based coverage for
the formatter/rename write paths, which hang/NPE in this sandbox — see
"Testing" below). Phase 5 is stretch and untouched.

## Build system

- **Use `./gradlew`**, not a system `gradle` — the wrapper is checked in
  (`gradle/wrapper/`, Gradle 9.3.0). No hardcoded JDK path anywhere; both
  `build.gradle.kts` and `shaded-sjsonnet/build.gradle.kts` declare a
  `java { toolchain { languageVersion = 21 } }` / `kotlin { jvmToolchain(21) }`
  block, so the Gradle *daemon* can run on whatever JVM is on PATH (JDK 25 on
  the dev machine) while compilation targets JDK 21. **Both modules need the
  toolchain block** — omitting it on `shaded-sjsonnet` causes a "compatible
  with JVM runtime version 25, but ... only compatible with 21" resolution
  failure, because Gradle attaches a JVM-version attribute to consumable
  configurations even for a module with no real sources.
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
  `StubIndexExtension`s need their own `<stubIndex key="..."
  implementation="..."/>` entries. This whole recipe compiled and passed on
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
- **`sjsonnet` has no native-function registration hook.** Verified via
  `javap` — neither `Interpreter` nor `Settings` (nor anything else in its
  public surface) lets you register additional `std.native(...)` functions.
  Tanka's Go-injected natives (`parseYaml`, `manifestJsonFromJson`, ...) can
  only ever be evaluated by shelling out to the real `tk`/`jsonnet` binary
  (ground-truth tier) — there's no way to give them real fast-tier JVM
  implementations. `tanka/TankaNativeFunctions.kt` only drives
  completion/hover for the function-name string, and says so in its doc.
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

## Grammar bugs hit and fixed (Jsonnet.bnf / Jsonnet.flex)

All three were caught by the `ParsingTestCase`-based tests in
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

## Testing: what works here and what doesn't

- **`ParsingTestCase`** (JUnit 3-style, `com.intellij.testFramework`) is the
  workhorse — fast, no IDE sandbox, real parser/PSI/reference behavior. Used
  in `JsonnetParsingTest.kt` and `JsonnetLexerTest.kt`. It provides a real
  `MockApplication`/`MockProject` but a **minimal** one: no `PsiFileFactory`,
  no `CodeStyleManager`, no `VirtualFileManager`/`LocalFileSystem`, no
  `CommandProcessor`. Anything needing those throws a clean NPE naming the
  missing service — that's a hard signal to stop and mark the code
  reviewed-but-untested rather than fight the fixture.
- **`BasePlatformTestCase`** (the heavier fixture with a real project/VFS)
  **hung indefinitely (16+ minutes, no output) when tried once in this
  sandbox environment** and was killed. Not reattempted since. If a future
  session wants real VFS/rename/reformat integration tests, budget for this
  risk explicitly — try it in a background task with a hard time-box, and be
  ready to abandon and fall back to code review if it hangs again. Don't
  assume it'll behave differently just because more code has accumulated.
- Consequence: `VirtualFilePath`/`VirtualFileImporter` (Phase 2),
  `TankaJpath` (Phase 3), and both write-paths added in Phase 4
  (`setName`/`handleElementRename`, and the native formatter's actual
  `CodeStyleManager.reformat()` invocation) are all **reviewed but not
  automated-tested** in this repo. Their *read-side* logic (pure PSI
  navigation with no service dependency — e.g. `TankaNativeFunctions`
  receiver detection, `TankaTkModule` access-chain walking,
  `PsiNameIdentifierOwner.getName()`) is tested where it could be split out.

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
