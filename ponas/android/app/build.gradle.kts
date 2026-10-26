import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One fixed key, committed (ponas/ponas.keystore), same as dock/: every build — CI or local —
// installs over the last one, so the child's saved progress survives updates.
val keystoreFile = rootProject.file("../ponas.keystore")
// One-off: -Pdebuggable=true builds a release that `adb run-as` can reach (used to restore
// saved progress from the old lt.eb07.ponas.debug app). Never for normal builds.
val debuggableRelease = (project.findProperty("debuggable") as String?) == "true"

android {
    namespace = "lt.eb07.ponas"
    compileSdk = 34

    defaultConfig {
        applicationId = "lt.eb07.ponas"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("PONAS_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("PONAS_VERSION_NAME") ?: "0.1-dev"
        resValue("string", "app_name", "Ponas")
    }

    signingConfigs {
        create("ponas") {
            storeFile = keystoreFile
            storePassword = "ponasponas"
            keyAlias = "ponas"
            keyPassword = "ponasponas"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = debuggableRelease
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ponas")
        }
        debug {
            // Separate package so a debug build can sit next to the release build
            // without signature conflicts.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Ponas debug")
            signingConfig = signingConfigs.getByName("ponas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 34 is deliberate (device is Android 14, no Play Store); don't let
        // Play-Store lint checks fail the release build.
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
