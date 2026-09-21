pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves JDK toolchain download repositories (e.g. for the checked-in
    // gradle/gradle-daemon-jvm.properties pin) so the Gradle daemon itself
    // can be auto-provisioned onto a specific JDK feature version without
    // hardcoding an absolute JDK path anywhere in the build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jsonnet-tanka"

include(":shaded-sjsonnet")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}
