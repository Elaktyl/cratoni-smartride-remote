import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials are read from keystore.properties in the project root.
// That file is machine-specific and never committed (see .gitignore); use
// keystore.properties.example as a template. If the file is absent or incomplete the
// build still works - the release artifact is then simply left unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun keystoreValue(key: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = keystoreValue("storeFile")
val releaseStorePassword = keystoreValue("storePassword")
val releaseKeyAlias = keystoreValue("keyAlias")
val releaseKeyPassword = keystoreValue("keyPassword")
val hasReleaseSigning = keystorePropertiesFile.isFile &&
    releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "de.werklade.cratoniremote"
    // 37 instead of 36: androidx.compose.ui 1.12.1 (Compose BOM 2026.09.00)
    // declares minCompileSdk=37 in its AAR metadata. minSdk/targetSdk stay at 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.werklade.cratoniremote"
        minSdk = 36
        targetSdk = 36
        // versionCode must increase with every published release; Android refuses to
        // install an update whose versionCode is not higher than the installed one.
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Only declared when all four values are available, so the configuration is
        // never half-initialised on machines without the keystore.
        if (hasReleaseSigning) {
            create("release") {
                // Path from keystore.properties is resolved against the project root.
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // null when keystore.properties is missing -> unsigned release build.
            // Deliberately no fallback to the debug key: an accidentally debug-signed
            // release can never be updated with the real key afterwards.
            signingConfig = signingConfigs.findByName("release")
            // Shrinking stays off: we publish exactly what was tested. Enable R8
            // (isMinifyEnabled/shrinkResources) only after testing a shrunk build.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Kotlin's jvmTarget defaults to targetCompatibility with built-in Kotlin.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
