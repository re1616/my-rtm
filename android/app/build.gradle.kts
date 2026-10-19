plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.taskly.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taskly.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // FCM is configured from Gradle properties / environment (no google-services.json is
        // committed). Empty values: the app runs without push (socket + periodic sync + alarms).
        fun prop(name: String): String = (project.findProperty(name) as String?) ?: System.getenv(name) ?: ""
        buildConfigField("String", "FCM_PROJECT_ID", "\"${prop("TASKLY_FCM_PROJECT_ID")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${prop("TASKLY_FCM_APP_ID")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${prop("TASKLY_FCM_API_KEY")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${prop("TASKLY_FCM_SENDER_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

configurations.configureEach {
    // Annotation processors (Room, Hilt) bundle a kotlin-metadata-jvm that predates Kotlin 2.4;
    // force the version matching the compiler so they can read 2.4 class metadata.
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
            useVersion(libs.versions.kotlin.get())
        }
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(project(":android:core:database"))
    implementation(project(":android:core:network"))
    implementation(project(":android:core:sync"))
    implementation(project(":android:core:designsystem"))
    implementation(project(":android:feature:tasks"))
    implementation(project(":shared"))

    // Room (needed at app level for DI/manual wiring)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datastore.preferences)

    // Hilt (applied without the Gradle plugin: classes extend the generated Hilt_* bases)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)
    implementation(libs.workmanager)
    implementation(libs.ktor.client.core)
    implementation(libs.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.kotlin.test)
}