# 0011. sjsonnet does have a tracing hook; the debugger is not built
Status: Accepted (was TODO item 13)

## Context
The plan and an early `AGENTS.md` said sjsonnet has no evaluation-tracing hook, which is why no debugger was planned. That came from a
shallow `javap` check, the same one that wrongly ruled out native functions.

## Decision
Record the hook and pin it with a test. `Interpreter` and `Evaluator` are non-final, `Interpreter.createEvaluator(...)` is public, and
`Evaluator.visitExpr(Expr, Eval[])` (the central dispatcher, where sjsonnet's own `Profiler` hooks in) is public. A subclass sees every
dispatched expression with `pos().currentFile()`/`offset()`, on the evaluating thread. `SjsonnetTracingHookTest` is the working example
and regression guard (mutation-checked). Verified: imported files and `Import` expressions fire; blocking in the hook is a working
breakpoint; the live `Eval[]` scope holds real values; on a realistic Tanka-style program every line that does work fired.

## Consequences
Limits, not worked around:
- Not every expression goes through `visitExpr`: eager arithmetic fast paths and the optimizer's constant folding skip it (no `Settings`
  switch; `createOptimizer` is public so folding could be neutered, the private fast paths can't).
- Some expressions have `offset() == -1`; skip them.
- Scope slots have no names; recover them from the AST (`ValidId` carries name and slot) — not tried.
- Order is demand-driven, so "step" follows forcing, not source order.
- `Interpreter`'s default logger is `null`; the Kotlin parameter must be nullable.
The debugger itself remains unbuilt, see [0015](0015-rejected-scope.md). Nothing in the plugin uses the hook yet.
