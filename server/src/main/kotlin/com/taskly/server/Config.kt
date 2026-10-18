package com.taskly.server

import com.taskly.server.config.ConfigValidator
import com.typesafe.config.ConfigFactory

// Configuration data classes — maps from application.conf `taskly` block
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val poolSize: Int
)

data class RedisConfig(
    val host: String,
    val port: Int
)

data class AuthConfig(
    val jwtSecret: String,
    val accessTokenExpiryMinutes: Long,
    val refreshTokenExpiryDays: Int
)

data class SyncConfig(
    val maxBatchSize: Int
)

data class RateLimitConfig(
    val tokensPerSecond: Int,
    val burstSize: Int,
    val perAccountMaxAttempts: Int = 5
)

object Config {
    /** Environment: "dev" or "prod". Defaults to "dev" if not set. */
    val TASKLY_ENV: String = System.getenv("TASKLY_ENV") ?: "dev"

    private val config = ConfigFactory.load().getConfig("taskly")

    // CFG-1: env overrides — env vars take precedence over config file defaults
    private fun envOrConfig(key: String, envVar: String? = null): String {
        val envValue = if (envVar != null) System.getenv(envVar) else null
        return envValue ?: config.getString(key)
    }

    private fun envOrConfigInt(key: String, envVar: String? = null): Int {
        val envValue = if (envVar != null) System.getenv(envVar) else null
        return envValue?.toIntOrNull() ?: config.getInt(key)
    }

    private fun envOrConfigLong(key: String, envVar: String? = null): Long {
        val envValue = if (envVar != null) System.getenv(envVar) else null
        return envValue?.toLongOrNull() ?: config.getLong(key)
    }

    val database = DatabaseConfig(
        host = envOrConfig("database.host", "PGHOST"),
        port = envOrConfigInt("database.port", "PGPORT"),
        database = envOrConfig("database.database", "PGDATABASE"),
        user = envOrConfig("database.user", "PGUSER"),
        password = envOrConfig("database.password", "PGPASSWORD"),
        poolSize = envOrConfigInt("database.poolSize")
    )

    val redis = RedisConfig(
        host = envOrConfig("redis.host", "REDIS_HOST"),
        port = envOrConfigInt("redis.port", "REDIS_PORT")
    )

    val auth = AuthConfig(
        jwtSecret = envOrConfig("auth.jwtSecret", "JWT_SECRET"),
        accessTokenExpiryMinutes = envOrConfigLong("auth.accessTokenExpiryMinutes"),
        refreshTokenExpiryDays = envOrConfigInt("auth.refreshTokenExpiryDays")
    )

    val sync = SyncConfig(
        maxBatchSize = envOrConfigInt("sync.maxBatchSize")
    )

    val rateLimit = RateLimitConfig(
        tokensPerSecond = envOrConfigInt("rateLimit.tokensPerSecond"),
        burstSize = envOrConfigInt("rateLimit.burstSize"),
        perAccountMaxAttempts = 5
    )

    /** Call after construction to validate all config. */
    fun validate() {
        ConfigValidator.validate(this)
    }
}