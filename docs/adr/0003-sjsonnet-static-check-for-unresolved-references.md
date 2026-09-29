# 0003. Cross-check unresolved names with sjsonnet's own analysis
Status: Accepted (was TODO item 3)

## Context
`JsonnetUnresolvedReferenceAnnotator` relies on a hand-rolled PSI resolver (`JsonnetResolver`). If its scoping rules diverge from
the evaluator's, names are wrongly flagged or missed, silently.

## Decision
Run sjsonnet's real name resolution as a second opinion: `SjsonnetStaticCheck.firstUnresolvedVariable(fileName, source)` builds an
`Interpreter` like `JsonnetEngine` does (`Importer.empty()`; imports don't leak names into a file's scope) and uses its public
`resolver()`. Cached per file with `CachedValuesManager` because it reparses the whole file. **Additive, not a replacement:** the
check is fail-fast (`StaticError.fail` is a Scala `Nothing`-returning throw), so it reports at most one name per pass; the PSI walk
stays the complete primary source.

## Consequences
- Correction to the plan, found by testing rather than reading `javap`: unresolved variables surface in `CachedResolver.parse()` as
  `Left(sjsonnet.Error)`, not in `StaticOptimizer.optimize()`. The `"Unknown variable"` string in `StaticOptimizer.class` is where the
  message lives, not where it fires. Two of seven tests failed on the first run before this was caught.
- `parse()`'s `Left` also carries real `ParseError`s; they are filtered by type so syntax errors aren't reported twice.
- Errors carry `stack()` frames with a plain character offset, mapping directly onto a PSI `TextRange`.
- Lesson: a promising string in a class file says where a message lives, not when it fires — write the call and a failing case first.
