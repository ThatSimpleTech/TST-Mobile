import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android side: accessibility observer and executor, overlay approval card, notification
// actions, kill switch tile, assistant role. Framework APIs only for now (no androidx), so
// tools/androidcheck can type-check these sources against the framework jar without an SDK.
plugins {
    // AGP comes from the root buildscript classpath (see the root build.gradle.kts), so no version here.
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.thatsimpletech.assist"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.thatsimpletech.assist"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 19
        versionName = "0.0.19"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
