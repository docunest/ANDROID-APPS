plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Baked-in defaults (CI passes them); both can be changed in the app.
val serverUrl: String = (project.findProperty("serverUrl") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: "https://map-pal-mike-base.vercel.app"
// Each CI build gets a higher versionCode, so the self-updater knows it's newer.
val buildNumber: Int = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.docunest.dock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.docunest.dock"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "UPDATE_REPO", "\"docunest/ANDROID-APPS\"")
    }

    signingConfigs {
        // One fixed key, committed (dock.keystore) — owner's call for a
        // 2-person app: every build installs over the last one, which the
        // silent self-updater needs.
        create("dock") {
            storeFile = file("../dock.keystore")
            storePassword = "dockdock"
            keyAlias = "dock"
            keyPassword = "dockdock"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("dock") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dock")
        }
    }

    buildFeatures {
        buildConfig = true
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
    // OpenStreetMap map view: no API key, no Google Play Services.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
