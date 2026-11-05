plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// App ID Agora: env/secret, gradle.properties, lalu nilai bawaan.
// Nilai bawaan wajib ada supaya build lokal tetap punya suara di room.
val agoraAppId: String =
    System.getenv("AGORA_APP_ID")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("agoraAppId") as String?)?.takeIf { it.isNotBlank() }
        ?: "a64c0421b7814ded8f0b2806f00e1a90"

// URL worker (token Agora + upload). Urutan: secret/env, gradle.properties, lalu nilai bawaan.
// Nilai bawaan memastikan upload gift tidak gagal walau secret kosong.
val agoraTokenUrl: String =
    System.getenv("AGORA_TOKEN_URL")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("agoraTokenUrl") as String?)?.takeIf { it.isNotBlank() }
        ?: ""

val googleWebClientId: String =
    System.getenv("GOOGLE_WEB_CLIENT_ID")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("googleWebClientId") as String? ?: "")

android {
    namespace = "com.example.googlelogin"
    compileSdk = 35

    // Aset roket (mp4/svga) ikut di dalam APK dan dibaca langsung dari assets,
    // jadi tidak boleh dikompres lagi oleh aapt.
    androidResources {
        noCompress += listOf("mp4", "svga")
    }

    defaultConfig {
        // Nama paket baru untuk rilis (Play Store / APKPure).
        // Namespace Kotlin dibiarkan com.example.googlelogin supaya kode tidak perlu diubah.
        applicationId = "com.rezochatvoice.exceo"
        minSdk = 24
        targetSdk = 35
        // Selalu naik (menit sejak 1970), jadi APK baru bisa menimpa APK lama baik dari GitHub maupun build lokal.
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: (System.getenv("REZO_BUILD") ?: "575"))
        // Pustaka native dipecah per jenis HP (lihat blok splits di bawah),
        // supaya satu APK tidak membawa kode untuk dua prosesor sekaligus.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "AGORA_TOKEN_URL", "\"$agoraTokenUrl\"")
        buildConfigField("String", "AGORA_APP_ID", "\"$agoraAppId\"")
    }

    // Satu APK per jenis prosesor: HP modern pakai arm64, HP lama pakai armv7.
    // Hasilnya jauh lebih ringan karena pustaka Agora (~33 MB per jenis) tidak
    // ikut dua-duanya. APK universal tetap dibuat sebagai cadangan.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
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

    // Android 16 (2016) menolak APK yang pustaka aslinya (.so) tidak pas
    // dengan halaman memori 16 KB pada HP tertentu, dan keluhan yang muncul
    // justru "ada masalah saat mengurai paket". Dengan pustaka dikemas
    // terkompresi (diekstrak saat pemasangan), APK jauh lebih kecil dan
    // tidak lagi bergantung pada perataan zip.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Semua pustaka Agora dikembalikan: dua pustaka yang sempat dibuang
            // (lip sync dan spatial audio) diduga membuat suara room senyap.
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
    implementation("io.livekit:livekit-android:2.30.0")

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
    // Login Facebook (token ditukar ke Firebase Auth).
    implementation("com.facebook.android:facebook-login:17.0.0")
    // Pembaca bzip2 untuk penggabung patch APK (tanpa library native).
    implementation("org.apache.commons:commons-compress:1.26.2")
}

// Kirim log build ke branch buildlog bila build gagal, agar penyebabnya bisa dibaca.
tasks.register<Exec>("pushBuildLog") {
    workingDir = rootProject.projectDir
    commandLine("bash", "ci-push-log.sh")
    isIgnoreExitValue = true
}
afterEvaluate {
    tasks.named("assembleRelease") { finalizedBy("pushBuildLog") }
}
