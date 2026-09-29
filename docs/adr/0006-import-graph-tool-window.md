# 0006. Imports tool window instead of a `DiagramProvider`
Status: Accepted (were TODO items 7 and 8)

## Context
The plan's Phase 5 wanted a `DiagramProvider`-based import graph, which can't be built or judged without running `runIde`.

## Decision
Build a lightweight "Jsonnet Imports" tool window (`imports/`): a tree of what the focused file imports transitively, or with
*Imported By* what imports it. The model (`JsonnetImportGraph`) is separate from the shell (`JsonnetImportGraphPanel`) and tested
directly (12 tests). The full diagram was closed as not built; the tool window is accepted as sufficient.
- **One resolution rule:** `TankaJpath.resolveImport` serves both `VirtualFileImporter` (evaluation) and the graph, so the view can't
  disagree with what `import` loads. It may return a directory; the graph treats that as unresolved, the importer rejects it.
- Finite tree from a graph: cycle → `CYCLE` leaf, already-expanded file → `SEEN_ABOVE` leaf. `vendor/` is shown but not expanded
  (and vendored importers omitted) unless *Expand vendor/* is on.
- Builds off the EDT (`ReadAction.nonBlocking`, cancelled by the next refresh); refreshes on focus change/toolbar, not per keystroke.
- `FileTypeIndex.getFiles` has no defined order (it made a test flaky), so results are sorted by path.
- Light fixtures don't instantiate tool windows: assert registration via `ToolWindowEP.EP_NAME.extensionList` and test panels via an
  internal synchronous `refreshNow()` seam.

## Consequences
Not visually verified (tracked in `TODO.md`). Not a rendered diagram.
