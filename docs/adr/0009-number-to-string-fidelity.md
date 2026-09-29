# 0009. Number-to-string divergence: won't fix
Status: Rejected (was TODO item 11)

## Context
`std.toString(0.1)` and `"" + 0.1` are `"0.10000000000000001"` in go-jsonnet and Tanka (17 significant digits) but `"0.1"` in sjsonnet.
This affects anything stringified (ConfigMap data, labels built by concatenation). Top-level numbers are unaffected (`tk`
re-serializes them). Huge integers: go prints them exactly, sjsonnet rounds. It can't be fixed through the std hook: concatenation
happens in the evaluator, so overriding `std.toString` alone would make the two paths disagree.

## Decision
Leave it. The divergence exists only in the plugin's own evaluation (Preview, Evaluate actions); exports go through the real `tk`,
which never touches sjsonnet. Fixing it needs a patched sjsonnet, not worth maintaining for a preview.

## Consequences
Documented in `README.md` and `docs/sjsonnet-gaps.md`. Revisit if sjsonnet fixes it upstream or a misleading preview is reported.
