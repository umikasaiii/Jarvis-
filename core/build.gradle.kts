plugins {
    // Kept in lockstep with the Android app's toolchain (gradle/libs.versions.toml).
    // The app runs Kotlin 2.2.21's stdlib and kotlinx-serialization 1.8.1 at
    // runtime; when :core was compiled against older versions, its classes were
    // linked against a stdlib/serialization the device didn't ship, which is a
    // recipe for a runtime NoClassDefFoundError that no JVM unit test can catch.
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.simone.jarvismobile"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
}

kotlin {
    // Build with the JDK available locally/in CI, but EMIT Java 17 bytecode:
    // the Android app compiles against Java 17, and Hilt/KSP generates Java
    // sources that import these classes — javac rejects newer class files
    // ("class file has wrong version 65.0, should be 61.0").
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
