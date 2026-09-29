# Architecture decision records

Each record captures one decision (or one closed question) that was made while building the plugin: the context,
what was decided, what was found on the way, and what it costs. They were extracted from the completed items of
`TODO.md`; the numbers in parentheses are the old item numbers. Open work stays in `../../TODO.md`. The original
phased plan is in [`../initial-plan.md`](../initial-plan.md) (historical).

Status is `Accepted` (in force), `Accepted, partly unverified`, or `Rejected` (decided not to build).

| # | Decision | Status |
|---|---|---|
| [0001](0001-platform-integration-testing.md) | Test write paths with `BasePlatformTestCase` (item 1) | Accepted |
| [0002](0002-stub-index-validated-on-real-corpus.md) | Stub index, validated on a real vendor tree (item 2) | Accepted |
| [0003](0003-sjsonnet-static-check-for-unresolved-references.md) | Cross-check unresolved names with sjsonnet's own analysis (item 3) | Accepted |
| [0004](0004-preview-tool-window.md) | Preview: TLA/ext vars, YAML, click-jump, off-EDT, unsaved buffers (items 4, 5) | Accepted |
| [0005](0005-color-settings-page.md) | Color settings page for semantic highlighting (item 6) | Accepted, partly unverified |
| [0006](0006-import-graph-tool-window.md) | Imports tool window instead of a `DiagramProvider` (items 7, 8) | Accepted |
| [0007](0007-marketplace-readiness.md) | Marketplace readiness, MIT licence, no Java dependency (item 9) | Accepted |
| [0008](0008-tanka-natives-and-sjsonnet-conformance.md) | Close sjsonnet gaps via `StdLibModule`, incl. Tanka natives (item 10) | Accepted |
| [0009](0009-number-to-string-fidelity.md) | Number-to-string divergence: won't fix (item 11) | Rejected |
| [0010](0010-flag-sjsonnet-only-std-functions.md) | Warn on sjsonnet-only `std` functions (item 12) | Accepted |
| [0011](0011-evaluation-tracing-hook.md) | sjsonnet does have a tracing hook; debugger not built (item 13) | Accepted |
| [0012](0012-jsonnetfmt-port-for-reformat-code.md) | Reformat Code is a byte-exact port of `jsonnetfmt` (items 14, 16–21) | Accepted |
| [0013](0013-block-model-typing-indentation.md) | Typing-time indentation follows `FixIndentation` (item 15) | Accepted |
| [0014](0014-typing-niceties.md) | Quote pairing and line-comment continuation (item 22) | Accepted |
| [0015](0015-rejected-scope.md) | Features deliberately not planned | Rejected |

## Template

```markdown
# NNNN. Title
Status: Accepted | Rejected
## Context
## Decision
## Consequences
```
