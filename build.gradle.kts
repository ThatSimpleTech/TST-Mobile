// Root build. Every plugin the subprojects share is declared here so they load in one
// classloader: the Kotlin plugins always, the Android Gradle Plugin only when an Android SDK
// is present (settings.gradle.kts decides), so a checkout without the SDK never resolves AGP.
buildscript {
    val androidSdkPresent = (gradle as org.gradle.api.plugins.ExtensionAware).extra.get("tstAndroidSdkPresent") as Boolean
    // Single source of truth for the AGP version is gradle/libs.versions.toml.
    val agpVersion = rootDir.resolve("gradle/libs.versions.toml").readLines()
        .first { it.trim().startsWith("agp ") || it.trim().startsWith("agp=") }
        .substringAfter('"').substringBefore('"')
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (androidSdkPresent) classpath("com.android.tools.build:gradle:$agpVersion")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
