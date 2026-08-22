plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.tinaai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tinaai.app"
        minSdk = 26 // Camera2Enumerator + WebRTC reliability; raise if you drop older devices
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-milestone3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // flip on once you've verified ProGuard rules for WebRTC/Firebase/MLKit
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core Android / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebRTC — precompiled AAR, no NDK build needed, works on GitHub Actions CI
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Firebase — Realtime Database for signaling (same as Talksy) + anonymous auth
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // On-device translation for live captions
    implementation("com.google.mlkit:translate:17.0.3")

    // TINA character animations
    implementation("com.airbnb.android:lottie-compose:6.5.2")
}
