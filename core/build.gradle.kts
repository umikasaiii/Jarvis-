plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.simone.jarvismobile"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // JarvisCoreClient (core/remote): plain-JVM HTTP + SSE, same version app/
    // already pins (gradle/libs.versions.toml) for the local dev/test client
    // OkHttp already ships in app/'s dependency graph. No Android dependency.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
