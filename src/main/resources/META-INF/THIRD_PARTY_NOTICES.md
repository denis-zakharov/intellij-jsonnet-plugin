# Third-party notices

This plugin bundles a shaded (package-relocated, see `shaded-sjsonnet/build.gradle.kts`) copy of
[sjsonnet](https://github.com/databricks/sjsonnet) and its runtime dependencies. Relocation only
renames packages; the code is otherwise unmodified. Licenses below are as declared in each
artifact's published POM for the versions this build resolves.

| Component | Version | License |
|---|---|---|
| com.databricks:sjsonnet | 0.7.4 | Apache License 2.0 |
| org.scala-lang:scala3-library, scala-library | 3.3.8 / 2.13.18 | Apache-2.0 |
| org.scala-lang.modules:scala-collection-compat | 2.14.0 | Apache-2.0 |
| com.lihaoyi:fastparse | 3.1.1 | MIT |
| com.lihaoyi:pprint, fansi | 0.9.6 / 0.5.1 | MIT |
| com.lihaoyi:ujson, upickle-core | 4.4.3 | MIT |
| com.lihaoyi:scalatags | 0.13.1 | MIT |
| com.lihaoyi:os-lib, os-zip | 0.11.8 | MIT |
| com.lihaoyi:mainargs | 0.7.8 | MIT |
| com.lihaoyi:geny, sourcecode | 1.1.1 / 0.4.3-M5 | MIT |
| org.yaml:snakeyaml | 2.6 | Apache License 2.0 |
| at.yawk.lz4:lz4-java | 1.10.3 | Apache License 2.0 |
| com.google.re2j:re2j | 1.8 | Go License (BSD-3-Clause style) |
| org.tukaani:xz | 1.11 | Public Domain |

The Apache-2.0 components are used under the terms of the
[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0). The MIT-licensed
components each carry their own copyright notice in their upstream repositories.

The Jsonnet grammar in `src/main/grammar/Jsonnet.bnf` was written from the
[Jsonnet language specification](https://jsonnet.org/ref/spec.html), not derived from any
language-server source.
