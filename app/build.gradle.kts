import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
}

// The PWA this app opens (gradle.properties).
val pwaUrl = URI(providers.gradleProperty("pwaUrl").get())
require(pwaUrl.scheme == "https") { "pwaUrl must be an https:// URL: $pwaUrl" }

// Release signing: keystore.properties (git-ignored; see README). Without it, release builds
// are signed with the debug key so they still install on emulators and devices.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "com.mustard.mobile"
    compileSdk = 36

    defaultConfig {
        // Not com.mustard.app (the React Native build in ../website) or com.mustard.pwa (the
        // Trusted Web Activity in ../pwa-android): all three can be installed side by side.
        applicationId = "com.mustard.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "PWA_URL", "\"$pwaUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.webkit:webkit:1.14.0")
    // Google's document scanner: camera, edge detection, cropping and cleanup in a UI that
    // Google Play services provides, so the app needs no camera permission of its own.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
}
