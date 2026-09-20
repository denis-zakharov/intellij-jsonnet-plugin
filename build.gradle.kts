import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.intellij.platform.gradle.tasks.GenerateLexerTask
import org.jetbrains.intellij.platform.gradle.tasks.GenerateParserTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.modules.json")
        pluginVerifier()
        zipSigner()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Embedded Jsonnet evaluator (Apache-2.0), pre-shaded (see :shaded-sjsonnet)
    // so its Scala 3 runtime never collides with JetBrains' own Scala plugin.
    implementation(project(":shaded-sjsonnet"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
}

intellijPlatform {
    pluginConfiguration {
        id.set("com.dz.intellij-jsonnet-tanka")
        name.set("Jsonnet + Tanka")
        version.set(providers.gradleProperty("pluginVersion"))

        ideaVersion {
            sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Grammar-Kit codegen: generate lexer (JFlex) + parser/PSI (BNF) into a
// generated-sources dir that's added to the main source set below.
val generatedSourcesDir = layout.buildDirectory.dir("generated/sources/grammarkit")

sourceSets {
    main {
        java.srcDirs(generatedSourcesDir)
    }
}

tasks.named<GenerateLexerTask>("generateLexer") {
    sourceFile.set(file("src/main/grammar/Jsonnet.flex"))
    targetRootOutputDir.set(generatedSourcesDir)
    pathToClass.set("com/dz/intellijjsonnet/lang/lexer/JsonnetLexer.java")
    purgeOldFiles.set(true)
}

tasks.named<GenerateParserTask>("generateParser") {
    sourceFile.set(file("src/main/grammar/Jsonnet.bnf"))
    targetRootOutputDir.set(generatedSourcesDir)
    pathToParser.set("com/dz/intellijjsonnet/lang/parser/JsonnetParser.java")
    pathToPsiRoot.set("com/dz/intellijjsonnet/lang/psi")
    purgeOldFiles.set(true)
}

tasks.withType<KotlinCompile> {
    dependsOn("generateLexer", "generateParser")
}

tasks.withType<JavaCompile> {
    dependsOn("generateLexer", "generateParser")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    test {
        useJUnitPlatform()
    }
}
