# 0004. Preview: TLA/ext vars, YAML, click-jump, off-EDT, unsaved buffers
Status: Accepted (were TODO items 4 and 5)

## Decision
All in `JsonnetPreviewPanel`, each with engine-level unit tests plus a `BasePlatformTestCase` driving the real panel.
- **TLA and ext vars** in two boxes, `name=string` / `name:=code` (`PreviewVars`). This fixed a latent bug: sjsonnet's Map-based
  `Interpreter` constructor treats *every* value as `ext-code`, so `env=prod` failed as an unresolved variable. Plain strings are now
  quoted as Jsonnet literals (`JsonnetEngine.VarValue(text, isCode)`).
- **YAML toggle** with sjsonnet's `YamlRenderer`, `quoteKeys = false` (like `tk show`; it also defaults to no trailing newline).
- **Ctrl/Cmd+click output → source:** `PreviewOutputPaths` maps the line to a value path (own JSON tokenizer; YAML via the shaded
  snakeyaml node tree), `SourceLocator` re-evaluates lazily and walks `Val.Obj`/`Val.Arr` to `Val.pos`, opening the file at that
  offset, including imports. It jumps to where the value was *produced*, not the field key: sjsonnet 0.7.4 folds constant objects
  into `ConstMember(value)` with no key position (found by a reflection probe; `{a:1}+{a:2}` merges into one such object).
- **Evaluation off the EDT:** pooled thread, one in flight, refreshes requested meanwhile collapse into one re-run, stale results
  are dropped, old output stays until the new is ready. Not `ReadAction.nonBlocking` on purpose: sjsonnet never checks for
  cancellation, so a held read action would block the next keystroke's write action; `VirtualFileText.read` takes short read
  actions per file instead.
- **Unsaved buffers:** `VirtualFileText.read` (live `Document` if loaded, else VFS) is the single read path for the root file,
  imports and error-position rendering, so `SourceLocator` offsets index exactly the string sjsonnet parsed. Preview listens to every
  Jsonnet document, so editing an imported file refreshes it.
- Navigating from Preview changes the editor selection, which Preview listens to; `jumpTarget` swallows that one event (mutation-checked).

## Consequences
- A running evaluation can't be killed, only ignored.
- `SourceLocator.locate` still runs on the EDT on Ctrl/Cmd+click (open item in `TODO.md`).
- The plan's full "every PSI `TextRange` → `Expr`" mapping was not built; click-jump didn't need it.
