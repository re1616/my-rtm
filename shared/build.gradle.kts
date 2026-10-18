plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js {
        // Library build consumed by the web app (web/ imports it as "@taskly/shared"):
        // ES module + TypeScript declarations in build/dist/js/productionLibrary.
        nodejs()
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
        compilations["main"].packageJson {
            customField("name", "@taskly/shared")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
    }
}
// Builds the JS library for the web app: ./gradlew :shared:webLibrary
tasks.register("webLibrary") {
    group = "build"
    description = "Builds the shared module as an ES module + .d.ts for web/ (build/dist/js/productionLibrary)"
    dependsOn("jsNodeProductionLibraryDistribution")
}
