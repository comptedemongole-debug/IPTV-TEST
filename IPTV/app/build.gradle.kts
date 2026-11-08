plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.perso.iptv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.perso.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 20
        versionName = "3.4"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // exigé par le décodeur audio FFmpeg (compatibilité Java 8+ sur les anciens Android)
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // Décodeur audio logiciel (AC3 / E-AC3 / DTS / TrueHD...) pour les appareils sans décodeur matériel (Chromecast Google TV).
    // Même version que Media3 (1.3.1). Utilisé uniquement en dernier recours : voir Audio.kt.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
