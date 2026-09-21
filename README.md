# Jsonnet + Tanka for IntelliJ

Native (non-LSP) IntelliJ support for [Jsonnet](https://jsonnet.org) and [Tanka](https://tanka.dev):
a real PSI grammar plus the [sjsonnet](https://github.com/databricks/sjsonnet) evaluator embedded
in-process. There is no language-server process to install, start or keep in sync.

Plugin id `io.github.denis-zakharov.jsonnet-tanka`. Requires an IntelliJ Platform IDE, build 251 (2025.1) or
newer. It depends only on the platform and the bundled JSON module — see [Compatibility](#compatibility).

## Features

**Editing**
- Syntax highlighting plus semantic highlighting of locals, parameters, fields and `std` calls
  (Settings › Editor › Color Scheme › Jsonnet).
- Go to definition, find usages and rename for locals, parameters and fields; Go to Symbol across the project.
- Import paths resolve the way Tanka's do: relative to the file, then the environment's `main.jsonnet`
  directory, `vendor/` and `lib/` under the `jsonnetfile.json` root.
- Completion and quick documentation for the standard library, Tanka's native functions and the `tk` module.
- Errors for unresolved references (cross-checked against sjsonnet's own scope analysis), an inspection
  for unused locals and hidden fields with quick fixes, parameter-name inlay hints.
- Formatter, folding, structure view, brace matching, commenting.

**Evaluation** (all in-process)
- **Jsonnet Preview** tool window — live output for the focused file, as JSON or YAML (toolbar toggle).
  - Ext vars and top-level arguments, one per line: `name=text` is a string, `name:=code` is Jsonnet code.
  - Ctrl/Cmd+click a line of output to jump to the source expression that produced that value, including
    into imported files.
  - Evaluates your unsaved editor buffers, including imported files you're editing in a split.
- **Jsonnet Imports** tool window — what the focused file imports (transitively), or, with *Imported By*,
  what imports it. `vendor/` is shown but not expanded unless you ask (*Expand vendor/*). Cycles and
  repeated dependencies are marked instead of repeated. Double-click or Enter to open.
- *Evaluate Jsonnet Expression* on a selection, with the file's top-level locals in scope.

**Tanka**
- *New › Tanka Environment*, JSON Schemas for `spec.json` and `jsonnetfile.json`, environment file icons.
- Run configurations and a gutter action for `jsonnet eval` and `tk show|diff|apply|export`, side-by-side
  comparison of two environments' `tk show` output, and a *Run jb install* quick fix on an unresolved
  `vendor/` import. These call **your own** `tk`, `jb` and `jsonnet` binaries from `PATH`; nothing else
  in the plugin needs them.

## Limitations

- Tanka's Go-injected native functions (`parseYaml`, `manifestJsonFromJson`, Helm, Kustomize, …) can't be
  evaluated by the embedded interpreter — sjsonnet has no hook to register them. Completion and hover know
  about them; to *run* them use the `tk` run configurations. A file that calls one shows an evaluation
  error in Preview.
- No step debugger; sjsonnet exposes no tracing API. *Evaluate Jsonnet Expression* is the substitute.
- Preview evaluates on the UI thread, so a very slow evaluation freezes the editor until it finishes.
- Click-to-source lands on the expression that produced a value (`replicas: 3` → the `3`), not on the
  field name; sjsonnet doesn't keep field-name positions for constant objects.
- The import view is a tree, not a rendered graph diagram.

## Compatibility

The plugin declares dependencies only on `com.intellij.modules.platform` and `com.intellij.modules.json`,
so it is not tied to a Java-capable IDE. It has been built and tested against IntelliJ IDEA Community
2025.1; other IDEs and newer builds have **not** been run through the Plugin Verifier yet
(`./gradlew verifyPlugin`).

## Building

```sh
./gradlew buildPlugin   # build/distributions/*.zip
./gradlew runIde        # sandbox IDE with the plugin installed
./gradlew test          # unit + light-platform tests
```

Use the checked-in Gradle wrapper. Gradle itself runs on JDK 21 (pinned in
`gradle/gradle-daemon-jvm.properties`); code targets JDK 21. The grammar (`src/main/grammar/Jsonnet.bnf`,
`Jsonnet.flex`) is compiled by Grammar-Kit/JFlex as part of the build.

## How it's put together

Two modules: the plugin, and `:shaded-sjsonnet`, which repackages sjsonnet and its Scala 3 runtime under
`io.github.denis_zakharov.jsonnettanka.shaded.*` so it can't collide with JetBrains' bundled Scala plugin. `AGENTS.md` and
`jsonnet-tanka-intellij-plugin-plan.md` record the design, the bugs found along the way and the testing
constraints; `TODO.md` is the backlog.

## Licenses

This project's own code is released under the [MIT License](LICENSE), with no warranty of any kind.

The plugin bundles sjsonnet and its dependencies (Apache-2.0, MIT and others) — see
[`THIRD_PARTY_NOTICES.md`](src/main/resources/META-INF/THIRD_PARTY_NOTICES.md).
