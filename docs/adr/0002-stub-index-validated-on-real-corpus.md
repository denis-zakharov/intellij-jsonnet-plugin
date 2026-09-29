# 0002. Stub index, validated on a real vendor tree
Status: Accepted (was TODO item 2)

## Context
`bind`/`field` are stub-indexed (`lang/stubs/`) so Find Usages and Go to Symbol are instant. The plan worried this would be
too slow or heavy over a Tanka `vendor/` tree of hundreds of files.

## Decision
Measure it on a real corpus: a sparse shallow clone of `jsonnet-libs/k8s-libsonnet`'s `1.34/` (688 `.libsonnet` files, ~7 MB),
copied into a `BasePlatformTestCase` project via a throwaway test (removed; the fixture stays outside the repo). Recipe:
`git clone --depth 1 --filter=blob:none --sparse <repo> && git sparse-checkout set <one-version-dir>`.

## Consequences
- Performance is fine: copying 688 files ~1.4–1.8 s, index build/query ~9–19 ms, an extra PSI error-check pass over all files
  ~0.8 s, heap delta ~150–290 MB. No `vendor/` entries leaked into `JsonnetBindIndex`/`JsonnetFieldIndex`.
- The real payoff was a parser bug: 687 of 688 files parsed, the failure being `local cronPatch = patch { mapContainers(f):: {...} }`.
  The grammar lacked `expr { ... }` object-mixin juxtaposition (`≡ expr + { ... }`), extremely common in Tanka code. Fixed by adding
  `objectLiteral` as a `postfixSuffix` alternative; 0/688 errors afterwards, three regression tests in `JsonnetParsingTest`.
- Lesson: rerun the real parser over a large external corpus whenever the grammar changes substantially; hand-written fixtures
  only cover what someone thought to write.
