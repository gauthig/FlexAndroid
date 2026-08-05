plugins {
    // AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` is
    // gone. The Compose compiler plugin is still a separate Kotlin plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gauthig.flexandroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gauthig.flexandroid"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned by default: `assembleRelease` produces an unsigned APK.
            // See docs/PUBLISHING.md for wiring a release keystore.
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // CI treats lint findings as build failures so regressions cannot land.
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // Dependency freshness is Dependabot's job, not the build's.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // ObsoleteSdkInt wants res/mipmap-anydpi-v26 merged into
            // res/mipmap-anydpi because minSdk is already 26. Doing that makes
            // AAPT2 fail to resolve @mipmap/ic_launcher at all — the unversioned
            // folder does not register the adaptive icon. The qualifier stays.
            "ObsoleteSdkInt",
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
