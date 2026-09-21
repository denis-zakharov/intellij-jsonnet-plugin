# sjsonnet vs go-jsonnet: what's missing, and what the plugin does about it

The plugin's fast tier evaluates with sjsonnet 0.7.4; Tanka (and so the ground truth) runs go-jsonnet.
This records where they differ, measured rather than assumed, and which differences the in-project
extension (`src/main/kotlin/io/github/denis_zakharov/jsonnettanka/engine/extension/`) closes.

Measured against go-jsonnet v0.22.0 (`testdata/`, 590 runnable cases) and Tanka v0.39. Re-run with
`scripts/sjsonnet-conformance.py` after bumping either.

## Headline

sjsonnet is close to conformant. Of 590 go-jsonnet test cases, 480 give identical output and 228 fail
in both (agreement; only success/failure is compared, not error text). **No case where go succeeds and
sjsonnet fails.** Everything below is what is left, plus what Tanka adds on top of go-jsonnet.

## Closed by the extension

| Gap | How found | Extension |
|---|---|---|
| `std.id` missing | std key diff: 164 go names vs 166 sjsonnet; only `id` (besides internal `$`-helpers) is absent | `StdExtras` |
| 14 std functions use different parameter names, so named-argument calls fail: `reverse`, `removeAt(i)`, `equals`, `objectFieldsEx`, `objectHasEx`, `hypot`, `modulo`, `sha1/256/512/3(s)`, `isNull(x)`, `escapeStringXML(str_)`, `manifestJson(value)` | `scripts/sjsonnet-conformance.py params`, each pair confirmed against the real `jsonnet` binary (e.g. `std.hypot(x=3, y=4)` is 5 in go, an error in sjsonnet) | `StdExtras.goParameterNames` (pure renames; positional calls unchanged) |
| Tanka's injected natives don't exist: `parseJson`, `parseYaml`, `manifestJsonFromJson`, `manifestYamlFromJson`, `escapeStringRegex`, `regexMatch`, `regexSubst`, `sha256` (`std.native('x')` was `null`, so calling it errored) | go's own `native1..6` cases, then Tanka's `pkg/jsonnet/native/funcs.go` | `TankaNatives`, outputs pinned to real `tk eval` in `TankaNativesTest` |

The hook is `StdLibModule(nativeFunctions, additionalStdFunctions).module()`, passed as `Interpreter`'s
`std` argument (`SjsonnetExtensions.std`). AGENTS.md used to say sjsonnet had no native-function hook;
that was checked against `Interpreter`/`Settings` only and was wrong.

Details worth knowing about the natives: `parseYaml` always returns an array (unlike `std.parseYaml`) and
resolves scalars per YAML 1.2 like yaml.v3 (`yes`/`on` stay strings, timestamps become RFC 3339
strings) rather than sjsonnet's YAML 1.1; regexes are RE2 on both sides (sjsonnet embeds RE2/J);
`manifestYamlFromJson` reimplements yaml.v3's layout (4-space indent, its quirky nesting inside sequence
items, quoting rules, `%g` numbers, natural key order) because no JVM YAML library emits it.

## Flagged, not fixed

| Difference | What the plugin does |
|---|---|
| sjsonnet has extras go-jsonnet v0.22 lacks: `std.regexFullMatch`, `regexPartialMatch`, `regexGlobalReplace`, `regexReplace`, `regexQuoteMeta`. Code using them previews fine but fails under `tk` with `Field does not exist` | `JsonnetSjsonnetOnlyStdInspection` (a warning, on by default) flags direct `std.<name>` uses and names the portable Tanka native where one exists (`regexMatch`, `regexSubst`, `escapeStringRegex`; none replaces only the first match, and none returns captures). The list is `SjsonnetOnlyStd`; `scripts/sjsonnet-conformance.py std` diffs both `std` key sets and reports drift |

Removing the functions instead would break code that runs fine in sjsonnet-based tools, and shadowing them with
erroring stubs would make the preview *less* useful than `tk` is. A warning keeps the preview honest about what will ship.

## Not closed

| Difference | Why it stays |
|---|---|
| **Numbers stringified inside Jsonnet**: `std.toString(0.1)` and `"" + 0.1` give `"0.10000000000000001"` in go-jsonnet *and Tanka*, `"0.1"` in sjsonnet. (Top-level numbers are not affected: `tk` re-serializes through `encoding/json`, so it prints `0.1` too.) 4 of the 6 `testdata` differences are this (`builtin_escapeStringJson`, `builtin_manifestTomlEx`, and huge integers `builtin_exp4`, `pow6`, which go prints exactly and sjsonnet rounds to 17 digits) | String concatenation lives in the evaluator, not in `std`, so overriding `std.toString` alone would make the two paths disagree. Needs an sjsonnet patch |
| `std.native(x=...)` (go names the parameter `x`, sjsonnet `name`) | `StdLibModule` appends `native` after merging the extras, so it can't be replaced |
| `helmTemplate`, `kustomizeBuild` natives | Shell out to external binaries even in Tanka; `std.native` returns `null` for them in the preview. Use the `tk` run configurations |
| sjsonnet accepts what go rejects: `std.base64` of code points above 255; recursion that go stops at 500 frames (`std.makeArray_recursive_evalutation_order_matters`) | Being more permissive can't produce a wrong preview of valid code, only miss an error |
| Linter (`jsonnet-lint`) and formatter | Not evaluator features; the plugin has its own inspections and formatter |

## Method

1. `std` key sets: `std.objectFieldsAll(std)` from both.
2. Differential run of every `testdata/*.jsonnet` through `jsonnet` and stock sjsonnet with go's own
   ext-var fixtures; outputs compared as JSON.
3. Parameter-name probe: every go parameter name called as a named argument on both.
4. Tanka's natives compared against `tk eval` in a scratch project (any directory containing a
   `jsonnetfile.json`); those outputs are the literals in `TankaNativesTest`.
