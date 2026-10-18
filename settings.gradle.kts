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

// Android modules are only included on local dev machines, not in CI.
// GitHub Actions runners have ANDROID_HOME pre-set but lack the required
// AGP/KSP plugin versions — they build Android via a dedicated job.
val isCi = System.getenv("CI") == "true"
val androidSdk = System.getenv("ANDROID_HOME")
if (!isCi || androidSdk != null) {
    include(":android:app")
    include(":android:core:database")
    include(":android:core:network")
    include(":android:core:sync")
    include(":android:core:designsystem")
    include(":android:feature:tasks")
}