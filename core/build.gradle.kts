import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone's platform-independent core: grammar, observation format, hint table, policy
// enforcer, meter, audit schema, redactor, wire-protocol client. No Android imports here,
// ever; that is what lets every promise be tested on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
