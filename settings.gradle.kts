pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // Central first: everything the pure-JVM core needs lives there, so a box with no
    // route to Google's Maven can still build and test core.
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "tst-mobile"

include(":core")

// :app needs the Android SDK. When there is none (a docs checkout, a box with no SDK),
// the pure-JVM :core still builds and tests, and :app is simply not part of the build.
val sdkDir: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.let { lp ->
        java.util.Properties().apply { lp.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
if (sdkDir != null && file(sdkDir).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("tst-mobile: no Android SDK found (ANDROID_HOME / local.properties); :app is skipped, :core only.")
}

// Type-check the app sources without an SDK: TST_ANDROID_CHECK=1 ./gradlew :tools:androidcheck:compileKotlin
if (System.getenv("TST_ANDROID_CHECK") == "1") {
    include(":tools:androidcheck")
}
