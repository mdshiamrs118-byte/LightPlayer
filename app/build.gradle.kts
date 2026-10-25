plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: set these environment variables (the GitHub workflow does it from your repository
// secrets) to sign the release APK with your own key. Without them it falls back to the debug key.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseKey = releaseKeystore != null && file(releaseKeystore).exists() &&
    releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "com.lightplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lightplayer"
        minSdk = 29          // Android 10
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            // Lightweight app: no shrinking needed.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        // Lint findings must never block the CI APK build.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-extractor:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
}