package com.taskly.server.config

import com.taskly.server.Config

fun Config.validate() = com.taskly.server.config.ConfigValidator.validate(this)

/**
 * Config validation (CFG-1..CFG-4).
 * Called at startup to reject dangerous configurations.
 */
object ConfigValidator {

    private val DEV_JWT_SECRET = "taskly-dev-secret-change-in-production-min-256bits!!"

    /** Validate the live config. Called at startup. */
    fun validate(cfg: Config) {
        validateAuth(cfg.auth.jwtSecret, cfg.env)
        validateDatabase(cfg.database.database)
    }

    // ============== Testable validation functions ==============

    /**
     * CFG-2: JWT_SECRET validation.
     * - Must be at least 32 bytes
     * - Must not be the dev default value outside TASKLY_ENV=dev
     */
    fun validateAuth(jwtSecret: String, env: String) {
        // Min 32 bytes
        val secretBytes = jwtSecret.toByteArray()
        if (secretBytes.size < 32) {
            throw IllegalStateException(
                "JWT_SECRET is too short: ${secretBytes.size} bytes, minimum 32 bytes required. " +
                "Generate with: openssl rand -base64 32"
            )
        }

        // Reject dev default outside dev mode
        if (jwtSecret == DEV_JWT_SECRET && env != "dev") {
            throw IllegalStateException(
                "JWT_SECRET is the development default value. " +
                "In TASKLY_ENV='${env}' you must set a unique production secret via JWT_SECRET env var. " +
                "Generate with: openssl rand -base64 32"
            )
        }
    }

    /**
     * CFG-3: test DB name validation.
     * If the DB name contains "test", it must end with "_test".
     */
    fun validateDatabase(databaseName: String) {
        if (databaseName.isNotBlank() && databaseName.uppercase().contains("TEST")) {
            if (!databaseName.lowercase().endsWith("_test")) {
                throw IllegalStateException(
                    "Database name '${databaseName}' contains 'test' but does not end with '_test'. " +
                    "Use a database name ending with '_test' for test environments to avoid confusion."
                )
            }
        }
    }
}