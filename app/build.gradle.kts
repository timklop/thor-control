import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.thorctl"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thorctl"
        minSdk = 34
        targetSdk = 36
        versionCode = 11
        versionName = "2.0"
    }

    // The installed app is signed with keystore/thorctl.jks, which is kept out of git. Without it
    // builds fall back to the debug key, and such an APK can't update the copy already installed.
    val thorKeystore = rootProject.file("keystore/thorctl.jks")
    signingConfigs {
        if (thorKeystore.exists()) {
            create("thor") {
                storeFile = thorKeystore
                storePassword = "thorctl"
                keyAlias = "thorctl"
                keyPassword = "thorctl"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("thor") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.findByName("thor") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
}
