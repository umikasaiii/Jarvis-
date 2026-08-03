pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JARVIS Mobile"

// The pure-Kotlin domain core lives in ./core as a standalone Gradle build so
// it can be compiled and unit-tested without the Android SDK. The Android app
// consumes it as a composite (included) build.
includeBuild("core")

include(":app")
