// Repackages sjsonnet + its Scala 3 runtime under a private namespace so the
// embedding plugin never collides with JetBrains' own bundled Scala plugin
// classpath. Consumed by the root plugin module as `project(":shaded-sjsonnet")`.
//
// The sjsonnet dependency lives in a dedicated `shaded` configuration (NOT
// `implementation`) so it never leaks into this project's `runtimeElements` as a
// transitive dependency — only the merged, relocated shadowJar output is exposed.
plugins {
    id("java")
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val sjsonnetVersion = "0.7.4"

val shaded: Configuration by configurations.creating

dependencies {
    shaded("com.databricks:sjsonnet_3:$sjsonnetVersion")
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        configurations = listOf(shaded)
        relocate("scala", "com.dz.intellijjsonnet.shaded.scala")
        relocate("sjsonnet", "com.dz.intellijjsonnet.shaded.sjsonnet")
        relocate("fastparse", "com.dz.intellijjsonnet.shaded.fastparse")
        relocate("ujson", "com.dz.intellijjsonnet.shaded.ujson")
        relocate("upickle", "com.dz.intellijjsonnet.shaded.upickle")
        relocate("geny", "com.dz.intellijjsonnet.shaded.geny")
        // NOTE: deliberately NOT relocating the bare "os" package (os-lib) — Shadow's
        // relocator rewrites matching string literals too, and "os" as a prefix
        // collides with unrelated "os.arch"/"os.name" System.getProperty() keys used
        // by transitive deps (e.g. lz4-java), corrupting them into garbage properties.
        relocate("pprint", "com.dz.intellijjsonnet.shaded.pprint")
        relocate("mainargs", "com.dz.intellijjsonnet.shaded.mainargs")
        relocate("scalatags", "com.dz.intellijjsonnet.shaded.scalatags")
        // IntelliJ's own platform bundles snakeyaml; relocate to avoid classpath skew.
        relocate("org.yaml.snakeyaml", "com.dz.intellijjsonnet.shaded.org.yaml.snakeyaml")
        mergeServiceFiles()
    }
}

// Make the shaded jar the artifact seen by other subprojects that declare
// `implementation(project(":shaded-sjsonnet"))`, with no transitive dependencies.
configurations {
    named("apiElements") {
        setExtendsFrom(emptyList())
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
    named("runtimeElements") {
        setExtendsFrom(emptyList())
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}
