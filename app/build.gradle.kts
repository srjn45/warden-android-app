plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.warden.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.warden.android"
        minSdk = 26
        // API 35 (Android 15) is the Play Store floor for new apps / updates as
        // of Aug 2025. enableEdgeToEdge() is already called in MainActivity, so
        // the edge-to-edge enforcement that ships with 35 is already handled.
        targetSdk = 35
        // Bumped for the v0.2.1 signed release (P0/P1/P2 shipped as versionCode 1
        // by mistake). CI overrides these from the git tag; see the -P defaults.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 2
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. The keystore never lives in the repo: locally it is read
    // from ANDROID_KEYSTORE_PATH (or app/release.keystore if present); in CI the
    // release workflow decodes ANDROID_KEYSTORE_BASE64 to that path. When no
    // keystore is available (ordinary local/debug builds, PR CI) the release
    // build type simply falls back to debug signing so nothing breaks.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        ?: rootProject.file("app/release.keystore").takeIf { it.exists() }?.path
    val releaseKeystore = keystorePath?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)

    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    debugImplementation(libs.androidx.ui.tooling)
}
