import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.openweigh"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.openweigh"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // OAuth 2.0 Web client ID used by Credential Manager (Sign in with Google) and the
        // AuthorizationClient. Supply it via `-PgoogleServerClientId=...` or a gradle.properties
        // entry; empty by default so the project builds without Google integration configured.
        val googleServerClientId = (project.findProperty("googleServerClientId") as String?) ?: ""
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
    }

    // Release signing. Real keystore credentials are read from a (git-ignored) keystore.properties
    // at the repo root; supply: storeFile, storePassword, keyAlias, keyPassword. When it is absent
    // (CI, fresh checkout, this build box) the release build falls back to the debug signing key so
    // `assembleRelease` still produces a runnable, R8-shrunk APK — just not one for Play upload.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Two product flavors on a single "store" dimension:
    //  - play: the full build with Google Drive backup + Sign in with Google (depends on GMS).
    //  - foss: a GMS-free build for F-Droid; cloud backup is a no-op (see src/foss). BLE, local
    //          storage and Health Connect are identical across both.
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
        }
        create("foss") {
            dimension = "store"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Required for Robolectric/Roborazzi to load Android resources & render Compose on the JVM.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            // The Google API / HTTP-client jars each ship duplicate META-INF metadata that the
            // resource merger cannot reconcile; drop the non-essential entries.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,LICENSE.md,NOTICE,NOTICE.txt,NOTICE.md}"
        }
    }
}

dependencies {
    // AndroidX core / lifecycle / activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Health Connect
    implementation(libs.androidx.health.connect.client)

    // Google Sign-In (Credential Manager) + Drive auth + Drive REST — PLAY FLAVOR ONLY.
    // The foss flavor links without any Google Play Services dependency.
    "playImplementation"(libs.bundles.googleAuth)
    "playImplementation"(libs.bundles.googleDrive)
    "playImplementation"(libs.kotlinx.coroutines.play.services)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Unit tests (JVM-only, plain JUnit4 — runs via `./gradlew testPlayDebugUnitTest`)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)

    // Headless Compose screenshot generation (Roborazzi + Robolectric) — used to produce the
    // F-Droid/Play store screenshots without a device/emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Screenshot (Roborazzi) tests are slow and only used to (re)generate store images. Keep them out
// of normal/CI unit-test runs; opt in with `-Pscreenshots` (e.g. recordRoborazziPlayDebug -Pscreenshots).
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshot/**")
    }
}
