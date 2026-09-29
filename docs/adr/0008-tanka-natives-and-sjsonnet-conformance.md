# 0008. Close sjsonnet gaps via `StdLibModule`, incl. Tanka natives
Status: Accepted (was TODO item 10)

## Context
The preview should agree with `tk`. Measured against go-jsonnet v0.22.0 `testdata/` and Tanka v0.39: sjsonnet is close (480/590
identical, 228 fail in both, none where go succeeds and sjsonnet fails). Full write-up: `docs/sjsonnet-gaps.md`; re-run
`scripts/sjsonnet-conformance.py` after any sjsonnet/go-jsonnet bump.

## Decision
Use `sjsonnet.stdlib.StdLibModule(nativeFunctions, additionalStdFunctions)`, passed as `Interpreter`'s 9th argument (`std`). The
earlier "no registration hook" verdict only checked `Interpreter`/`Settings`. `engine/extension/SjsonnetExtensions.std` provides
`std.id`, go's parameter names for 14 functions (named-arg calls) and Tanka's 8 pure natives (`parseJson`, `parseYaml`,
`manifestJsonFromJson`, `manifestYamlFromJson`, `escapeStringRegex`, `regexMatch`, `regexSubst`, `sha256`), with outputs pinned to real
`tk eval` in `TankaNativesTest`. **Every `Interpreter` must pass it** (`JsonnetEngine`, `SjsonnetStaticCheck`, `StdLibRegistry`).

## Consequences
- The ground-truth comparison caught: `yes`/`on` parsed as booleans (SnakeYAML is YAML 1.1, yaml.v3 isn't), `null` never matching
  ujson's `Null` object (an object, match `` `Null$`.`MODULE$` ``), and a wrong `%g` threshold.
- Quirks: additional std functions override built-ins, but `native` is appended *after* the merge; `Error.fail` returns Scala's `Nothing$`.
- Not verified: `manifestYamlFromJson` is a hand-written emitter; exotic strings weren't compared with `tk` (tracked in `TODO.md`).
- Not covered: `std.native(x=...)`, and `helmTemplate`/`kustomizeBuild` (external binaries; `std.native` is `null` for them).
