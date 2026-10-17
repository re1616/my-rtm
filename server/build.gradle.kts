plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.flywaydb.flyway") version libs.versions.flyway.get()
    application
}

group = "com.taskly"
version = "0.1.0"

application {
    mainClass.set("com.taskly.server.ApplicationKt")
}

repositories {
    mavenCentral()
}



dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // Database
    implementation(libs.jooq)
    implementation(libs.jooq.kotlin)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    // Logging
    implementation(libs.logback.classic)

    // Test
    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    environment("PGHOST", System.getenv("PGHOST") ?: "localhost")
    environment("PGPORT", System.getenv("PGPORT") ?: "5432")
    environment("PGDATABASE", System.getenv("PGDATABASE") ?: "taskly_test")
    environment("PGUSER", System.getenv("PGUSER") ?: "taskly")
    environment("PGPASSWORD", System.getenv("PGPASSWORD") ?: "taskly")
    environment("REDIS_HOST", System.getenv("REDIS_HOST") ?: "localhost")
    environment("REDIS_PORT", System.getenv("REDIS_PORT") ?: "6379")
}