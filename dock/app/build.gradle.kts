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
            // Shrunk: the car downloads updates over metered mobile data (was 1.5 MB, now ~1/7).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("dock")
        }
    }

    packaging {
        // Dex stored uncompressed: a small code change is then a small binary
        // patch for the self-updater (DeltaPatch.kt, tools/delta.py).
        dex.useLegacyPackaging = false
        resources.excludes += listOf("kotlin/**", "**/*.kotlin_builtins", "kotlin-tooling-metadata.json", "META-INF/*.version", "META-INF/*.kotlin_module")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        // Smaller dex: drop Kotlin's runtime null-check calls (types are still checked at compile time).
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

// CI passes -PdeltaCheckDir=<dir> so DeltaPatchTest rebuilds each real patch
// with the app's own code before the release is published.
tasks.withType<Test>().configureEach {
    (project.findProperty("deltaCheckDir") as String?)?.let { systemProperty("deltaCheckDir", it) }
}

dependencies {
    // OpenStreetMap map view: no API key, no Google Play Services.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Plain JVM tests for the logic that doesn't need a device (./gradlew testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")
}
