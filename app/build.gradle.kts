plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.simone.jarvismobile"
    // compileSdk is set to the latest stable platform. Confirm the exact
    // installed platform with `sdkmanager --list` on the build machine.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simone.jarvismobile"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Short build id (git SHA in CI, "local" otherwise) shown in the UI so we
        // can verify which build is actually installed on the device.
        val buildId = (System.getenv("GITHUB_SHA") ?: "local").take(7)
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Prioritize the Honor 200 / Snapdragon 7 Gen 3 target (arm64-v8a). A
        // second ABI can be added when native (llama.cpp / sherpa-onnx) modules
        // land in phases 2–3.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // A fixed, committed DEBUG keystore so every build (local or CI) is signed
        // with the same key. This lets debug APKs update in place without an
        // uninstall. Debug-only; not a release key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Secrets are never baked into BuildConfig (docs/SECURITY.md §21).
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Pure-Kotlin domain core (composite build). Contains the conversation state
    // machine, router, tool protocol, security policies, retrieval ranking.
    implementation("com.simone.jarvismobile:jarvis-core")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // On-device LLM (Phase 3, LiteRT-LM) + SAF document access for model import.
    implementation(libs.litertlm.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.onnxruntime.android)

    // Live Translator: ML Kit on-device translation + language identification.
    // Models are downloaded at runtime via RemoteModelManager, never bundled.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Home-screen widgets (Jetpack Glance). Widgets are thin: they only fire the
    // existing deep links/broadcasts, never their own controllers.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // Offline PDF text extraction for the document-import pipeline (Apache PDFBox
    // Android port). Fully on-device; DOCX is parsed with java.util.zip, no dep.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Optional, opt-in on-device OCR for imported images (ML Kit, bundled Latin
    // model — no network). Off by default so imports stay fast.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Unit tests.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented / Compose UI tests.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
