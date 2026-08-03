// Standalone Gradle build for the pure-Kotlin domain core of JARVIS Mobile.
//
// This module intentionally has ZERO Android dependencies so that the
// conversation engine (state machine, router, tool protocol, security
// policies, memory ranking, log redaction) can be compiled and unit-tested
// on any JVM — including CI environments without the Android SDK.
//
// The Android application (../app) consumes this build via `includeBuild`.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jarvis-core"
