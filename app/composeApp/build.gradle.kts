import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidApplication)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // JNA's `@aar` variant ships the .so files needed for UniFFI runtime
            implementation("${libs.jna.get()}@aar")
            implementation(libs.koin.android)
        }
    }
}

android {
    namespace = "price.sats"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "price.sats"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        // Cargo-ndk writes .so files here; AGP picks them up automatically.
        jniLibs.srcDirs("src/androidMain/jniLibs", "../android/src/main/jniLibs")
        res.srcDirs("src/androidMain/res")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Release signing is driven entirely by env vars so the keystore + passwords
    // never live in version control. Local builds without these vars set fall
    // through to an unsigned release APK (which Gradle is happy to produce —
    // it just can't be installed without manual `apksigner` work). CI populates
    // them from GitHub secrets (see .github/workflows/release.yml).
    val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
    val releaseSigningConfigured = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Phase 13: R8 on. Keep-rules in proguard-rules.pro protect the
            // UniFFI bindings and JNA reflection paths that R8 cannot see
            // statically. `proguard-android-optimize.txt` is AGP's tuned
            // default and includes the Compose / Kotlin / AndroidX rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
