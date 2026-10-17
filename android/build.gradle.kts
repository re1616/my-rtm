plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.taskly"
version = "0.1.0"

repositories {
    mavenCentral()
}



dependencies {
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}