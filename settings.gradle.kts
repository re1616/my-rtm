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
include(":android:app")
include(":android:core:database")
include(":android:core:network")
include(":android:core:sync")
include(":android:core:designsystem")
include(":android:feature:tasks")