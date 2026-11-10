plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.iastudio.vm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.iastudio.vm"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.9"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Clé fixe : les mises à jour s'installent par-dessus l'ancienne version sans rien perdre.
    signingConfigs {
        create("fixed") {
            storeFile = file("fixed.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Les moteurs natifs (libllama_server.so, libsd.so) sont des exécutables placés dans jniLibs :
    // Android les extrait dans nativeLibraryDir où ils peuvent être lancés.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
