plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.taskly.android.feature.tasks"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    implementation(project(":shared"))
    implementation(project(":android:core:database"))
    implementation(project(":android:core:network"))
    implementation(project(":android:core:sync"))
    implementation(project(":android:core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata.jvm)

    testImplementation(libs.kotlin.test)
}