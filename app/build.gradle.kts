import java.util.Properties
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
        versionCode = 14
        versionName = "2.2.1"
    }

    // Release builds are signed with keystore/thorctl.jks; it and its passwords
    // (keystore/keystore.properties: storePassword, keyAlias, keyPassword) are kept out of git.
    // Without them builds fall back to the debug key, and such an APK can't update an install
    // that was signed with the release key.
    val thorKeystore = rootProject.file("keystore/thorctl.jks")
    val thorKeyProps = Properties().apply {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (thorKeystore.exists() && thorKeyProps.getProperty("storePassword") != null) {
            create("thor") {
                storeFile = thorKeystore
                storePassword = thorKeyProps.getProperty("storePassword")
                keyAlias = thorKeyProps.getProperty("keyAlias")
                keyPassword = thorKeyProps.getProperty("keyPassword")
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
