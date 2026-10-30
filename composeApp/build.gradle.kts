import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
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
        versionCode = 19
        versionName = "0.2.0-beta7"
    }
    // Leave out the Google-encrypted dependency block: F-Droid rejects it and it is not needed elsewhere.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
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
