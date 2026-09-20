# Changelog

All notable changes to this plugin. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0] - Unreleased

First release.

### Language support
- Jsonnet (`.jsonnet`) and Libsonnet (`.libsonnet`) file types with a PSI grammar covering the full
  language, including object mixin juxtaposition (`expr { ... }`), text blocks, comprehensions and
  computed field names.
- Syntax highlighting, semantic highlighting (locals / parameters / fields / `std` calls) and a
  Color Scheme settings page for all of it.
- Resolution for locals, parameters and fields: go to definition, find usages, rename; Go to Symbol
  backed by a stub index (files under `vendor/` are deliberately not indexed).
- Member access resolves statically through imports, `local` bindings, `self` / `super` / `$`,
  `+` and `expr { ... }` composition (including `field+:`), string indexes and calls of functions
  defined in source: `lib.util.helper`, `deployment.new(...).spec` and so on navigate to the declaration.
- Import path references resolving relative to the file, then Tanka's jpath
  (`main.jsonnet` directory, `vendor/`, `lib/`): go to definition, path completion, and file
  renames/moves update the path.
- Completion for locals, parameters, loop variables, keywords, `std`, and object fields after `.`
  (auto-popup on `.`); completion and documentation for `std`, Tanka's native functions and the
  `tk` module.
- Unresolved-reference errors, cross-checked against sjsonnet's own scope analysis; an "unused local
  or hidden field" inspection with quick fixes; parameter-name inlay hints.
- Formatter, code folding, structure view, brace matching, commenter.

### Evaluation
- Embedded sjsonnet evaluator (shaded, no external process).
- **Jsonnet Preview** tool window: JSON or YAML output, ext vars and top-level arguments
  (`name=string` / `name:=code`), Ctrl/Cmd+click an output line to jump to the source expression that
  produced the value (across imports), evaluation of unsaved editor buffers.
- **Jsonnet Imports** tool window: transitive imports of the focused file, or its importers.
- Evaluate Jsonnet Expression action.

### Tanka
- New Tanka environment action; JSON Schemas for `spec.json` and `jsonnetfile.json`; environment icons.
- Run configurations and a gutter action for `jsonnet` / `tk show|diff|apply|export`, environment
  comparison via `tk show`, and a `jb install` quick fix (all optional; they call the user's own binaries).

### Known limitations
- Tanka's Go-injected native functions (`parseYaml`, Helm, Kustomize, ...) can't be evaluated by the
  embedded interpreter — use the `tk` run configurations for those.
- No step debugger (sjsonnet exposes no tracing hook).
- Preview evaluates on the UI thread, so a very slow evaluation blocks the editor until it finishes.
