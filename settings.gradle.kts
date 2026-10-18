rootProject.name = "taskly"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":server")

// Android modules are only included when the Android SDK is available
// (local dev machine or dedicated Android CI job). Without it, Gradle
// would fail at configuration time trying to resolve AGP/KSP plugins.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (androidSdk != null || File(settingsDir, "local.properties").exists()) {
    include(":android:app")
    include(":android:core:database")
    include(":android:core:network")
    include(":android:core:sync")
    include(":android:core:designsystem")
    include(":android:feature:tasks")
}