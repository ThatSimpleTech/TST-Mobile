// Root build. Only JVM-side plugins are declared here so that a checkout without the
// Android SDK never has to resolve the Android Gradle Plugin. :app declares AGP itself.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
