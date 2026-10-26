plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val agoraAppId: String =
    System.getenv("AGORA_APP_ID")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("agoraAppId") as String? ?: "")

// URL worker (token Agora + upload). Urutan: secret/env, gradle.properties, lalu nilai bawaan.
// Nilai bawaan memastikan upload gift tidak gagal walau secret kosong.
val agoraTokenUrl: String =
    System.getenv("AGORA_TOKEN_URL")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("agoraTokenUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: "https://rezo-agora-token.bangexx01.workers.dev"

val googleWebClientId: String =
    System.getenv("GOOGLE_WEB_CLIENT_ID")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("googleWebClientId") as String? ?: "")

android {
    namespace = "com.example.googlelogin"
    compileSdk = 35

    defaultConfig {
        // Nama paket baru untuk rilis (Play Store / APKPure).
        // Namespace Kotlin dibiarkan com.example.googlelogin supaya kode tidak perlu diubah.
        applicationId = "com.rezochatvoice.exceo"
        minSdk = 24
        targetSdk = 35
        // Selalu naik (menit sejak 1970), jadi APK baru bisa menimpa APK lama baik dari GitHub maupun build lokal.
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "AGORA_TOKEN_URL", "\"$agoraTokenUrl\"")
        buildConfigField("String", "AGORA_APP_ID", "\"$agoraAppId\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // Tanda tangan lengkap skema V1 + V2 + V3 untuk rilis (APKPure / Play Store).
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("io.agora.rtc:voice-sdk:4.5.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
