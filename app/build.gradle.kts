import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// The release key never lives in the repository. Locally it is described by a properties
// file outside the checkout; in CI the same four values arrive as environment variables,
// with the keystore itself written from a base64 secret before the build.
val keystoreProperties = Properties().apply {
    val local = File(System.getProperty("user.home"), ".local/share/taplex/keystore.properties")
    if (local.exists()) local.inputStream().use { load(it) }
}

fun signingValue(name: String, environment: String): String? =
    System.getenv(environment) ?: keystoreProperties.getProperty(name)

android {
    namespace = "de.tieo.taplex"
    compileSdk = 34
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "de.tieo.taplex"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // The bundled OCR models make a universal APK enormous; one ABI per APK keeps an
    // install under 40 MB.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "TAPLEX_KEYSTORE")
            if (store != null && File(store).exists()) {
                storeFile = File(store)
                storePassword = signingValue("storePassword", "TAPLEX_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "TAPLEX_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "TAPLEX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // An unsigned release APK is still worth building on a machine that has no key,
            // so the config is only attached when there is one.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
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
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
