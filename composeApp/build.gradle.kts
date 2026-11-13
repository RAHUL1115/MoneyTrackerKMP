import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.biometric)
            // biometric 1.1.0 pulls an old fragment whose FragmentActivity rejects modern request codes.
            implementation(libs.androidx.fragment)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android.driver)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.junit)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                // Screenshot tests (ScreenshotTest.kt): Compose drawn by Robolectric's native graphics, compared by Roborazzi.
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.robolectric)
                implementation(libs.roborazzi)
                implementation(libs.roborazzi.compose)
            }
        }
    }
}

sqldelight {
    databases {
        create("MoneyDatabase") {
            packageName.set("app.moneytracker.db")
        }
    }
}

android {
    namespace = "app.moneytracker"
    compileSdk = 36

    defaultConfig {
        // Permanent store identity (reverse of amrahul.in). Kotlin code stays in app.moneytracker: `in` is a Kotlin keyword.
        applicationId = "in.amrahul.moneytracker"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Bumped by hand for each release and tagged v<versionName>. Kept literal so F-Droid builds from the
        // tag get the same version as GitHub and Play; versionCode must go up by at least 1 every release.
        versionCode = 48
        versionName = "0.4.8"
    }
    // Leave out the Google-encrypted dependency block: F-Droid rejects it and it is not needed elsewhere.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // Robolectric reads the merged resources and manifest (screenshot tests need the theme and fonts).
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Release key comes from the environment (CI secrets); never stored in the repo. Without it, release
    // builds are left unsigned: the debug key in keystore/ is public and must never sign a release.
    val releaseStore = System.getenv("RELEASE_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            // PKCS12 keys (keytool default) have one password for store and key; a separate one is optional.
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("RELEASE_STORE_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            // Separate app, so a debug build (signed with the public key) can never replace a release install.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// The empty activity the screenshot tests draw into.
dependencies {
    debugImplementation(libs.compose.ui.test.manifest)
}

// Screenshot baselines live next to the tests and are committed: record with recordRoborazziDebug, check with
// verifyRoborazziDebug.
roborazzi {
    outputDir.set(file("src/androidUnitTest/screenshots"))
}

// Code-quality gate: the limits in .claude/skills/kotlin-readable-code. Any finding fails the build; a rare,
// deliberate exception is a local @Suppress with a one-line reason next to it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/commonMain/kotlin", "src/androidMain/kotlin")
}

// detekt 1.23 is built against Kotlin 2.0; pin its own classpath so the app's newer Kotlin doesn't leak in.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}
