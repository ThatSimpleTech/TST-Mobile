import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Compiles app/src/main/kotlin against Robolectric's android-all framework jar so the
// Android sources get type-checked on a machine that has no Android SDK. It is a
// type check, not a build: no resources, no manifest merge, no dex. assembleDebug on a
// machine with the SDK (or CI) is still the real gate.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir(rootProject.file("app/src/main/kotlin"))
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(libs.android.all)
}
