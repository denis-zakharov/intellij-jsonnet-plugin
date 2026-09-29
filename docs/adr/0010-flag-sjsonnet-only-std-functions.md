# 0010. Warn on sjsonnet-only `std` functions
Status: Accepted (was TODO item 12)

## Decision
`JsonnetSjsonnetOnlyStdInspection` (warning, on by default) flags `std.regexFullMatch`, `regexPartialMatch`, `regexGlobalReplace`,
`regexReplace`, `regexQuoteMeta`: the only members sjsonnet has and go-jsonnet v0.22 lacks (diff of `std.objectFieldsAll(std)`). They
work in the preview and fail under `tk`. The message names the portable Tanka native where one exists (`regexMatch`, `regexSubst`,
`escapeStringRegex`) with the caveats. Data in `stdlib/SjsonnetOnlyStd.kt`.
- Scope is direct `std.name` only (like std hover/completion); `std['x']` and `local s = std; s.x` aren't followed; a local or
  parameter named `std` is left alone; `vendor/` is skipped.
- **No quick fix:** the natives take different arguments and return booleans instead of match objects, so a rewrite would change behaviour.

## Consequences
`scripts/sjsonnet-conformance.py std` diffs the two key sets and exits 1 if the list has drifted — run it after a bump. Tests:
`JsonnetSjsonnetOnlyStdInspectionTest` (shadowing and `vendor/` guards mutation-checked), `SjsonnetOnlyStdTest`.
