# 0015. Features deliberately not planned
Status: Rejected. Revisit only if the premise changes.

- **Online completion for `jsonnetfile.json` package names/versions.** Dropped, not deferred: `jb` resolves dependencies by direct git remote
  URL, not against a central registry, so there is nothing to complete against. Revisit if Tanka/`jb` grows a package index.
- **`helmTemplate` / `kustomizeBuild` natives on the JVM.** They shell out to external binaries even in real Tanka, so a JVM version would
  be a worse `tk`. (Tanka's pure natives are done: [0008](0008-tanka-natives-and-sjsonnet-conformance.md).)
- **Step-through debugging into sjsonnet.** The premise that made it impossible ("no public hook") was wrong ([0011](0011-evaluation-tracing-hook.md)),
  but it is a phase of its own: an `XDebugProcess`/run configuration, a breakpoint type mapped to `Position`s, a suspend/resume protocol,
  variable names for the scope view, evaluation off the EDT, and living with the fast-path gaps. `EvaluateJsonnetExpressionAction` is the
  substitute. Revisit if step-through becomes a priority.
- **Inferred merge-result inlay hints on composed objects.** Fundamentally imprecise without full evaluation (computed field names, imports
  and comprehensions defeat static inference). Preview is the precise answer; a second approximate one risks false hints.
- **`DiagramProvider` import graph** and **patched sjsonnet for number formatting**: see [0006](0006-import-graph-tool-window.md) and
  [0009](0009-number-to-string-fidelity.md).
