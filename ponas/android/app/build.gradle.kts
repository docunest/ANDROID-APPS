import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables set by .github/workflows/android.yml.
// Without them the release APK is left unsigned (the debug APK always builds).
val keystorePath: String? = System.getenv("PONAS_KEYSTORE")
val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

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
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Separate package so a debug build can sit next to the release build
            // without signature conflicts.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Ponas debug")
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
