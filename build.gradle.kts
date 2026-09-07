// Root build. The Kotlin plugins are declared here (not applied) so every subproject shares
// one Kotlin Gradle Plugin version; the Android Gradle Plugin is deliberately not, so that a
// checkout without the Android SDK never has to resolve it. :app declares AGP itself.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
