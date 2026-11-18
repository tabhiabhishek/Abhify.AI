plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional GPU backend. CPU is the supported baseline; Vulkan is built only when asked for
// (-Pabhify.vulkan=true) and is used at run time only after the per-device check passes.
val vulkan = (findProperty("abhify.vulkan") as String?).toBoolean()
// A local llama.cpp checkout (same pinned commit) can replace the download: -Pabhify.llamaDir=D:/AndroidDev/src/llama.cpp
val llamaDir = findProperty("abhify.llamaDir") as String?

android {
    namespace = "ai.abhify.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "ai.abhify.android"      // permanent once released: updates need the same id and signing key
        minSdk = 29                              // Android 10, see docs/ANDROID_APP.md "Minimum Android version"
        targetSdk = 36
        versionCode = 5040099                    // major*1000000 + minor*10000 + patch*100 + stage (rc.N = N, final = 99)
        versionName = "5.4"                      // owner's request (2026-11-18): "make next build" (Personal Voice Companion)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Signed-update source and trusted public key(s) "keyId=base64,...". Empty = updates not configured
        // (the owner decides the host and creates the production key; nothing is preset here).
        buildConfigField("String", "UPDATE_URL", "\"${findProperty("abhify.updateUrl") ?: ""}\"")
        buildConfigField("String", "UPDATE_KEYS", "\"${findProperty("abhify.updateKeys") ?: ""}\"")
        // Online server (accounts, sync, online AI). Empty = none; the user can enter one in the app. Never a guessed URL.
        buildConfigField("String", "CLOUD_URL", "\"${findProperty("abhify.cloudUrl") ?: ""}\"")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DABHIFY_VULKAN=${if (vulkan) "ON" else "OFF"}",
                )
                if (llamaDir != null) arguments += "-DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=$llamaDir"
                if (vulkan) {
                    (findProperty("abhify.vulkanArgs") as String?)?.split(' ')?.filter { it.isNotBlank() }?.let { arguments += it }
                }
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // x86_64 only so emulators can run UI tests
            // Internal development identifier (2026-11-18): the app version stays 5.4 / 5040099 (no version change without
            // the owner's approval); internal builds read "5.4-dev.1" and are written only to build/internal/.
            versionNameSuffix = "-internal.6.2-dev.1"   // 6.2 internal milestone (real-time intelligence; no 6.1.1 maintenance build existed); versionCode unchanged (an official 6.x version needs owner approval)
        }
        release {
            isMinifyEnabled = false
            ndk { abiFilters += listOf("arm64-v8a") }
            // No release signing config here on purpose: a production key is the owner's decision.
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Extracted on install: llama.cpp finds its CPU variants (libggml-cpu-*.so) by scanning nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }

    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // 6.1 cleanup: lifecycle-viewmodel-compose removed (no viewModel() call or import; AndroidViewModel comes from lifecycle)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    // 6.1 cleanup: ui-tooling-preview removed (no @Preview anywhere)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.play.services.auth)      // Google Drive sign-in: official Authorization API, scope drive.file only
    implementation(libs.mlkit.text.recognition)  // on-device OCR, Latin script, bundled model (images never leave the phone)
    implementation(libs.pdfbox.android) {       // PDF text extraction on the phone (Apache-2.0)
        exclude(group = "org.bouncycastle")      // only for certificate-encrypted PDFs; v1.72 has known CVEs. Such PDFs get a clear message.
    }

    testImplementation(libs.junit)
    testImplementation(libs.json)                // real org.json for JVM tests (Android's is a stub there)
    testImplementation(libs.kxml2)               // real XML pull parser for the news feed tests
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)         // runs the v5 migration SQL on a real SQLite in JVM tests

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
