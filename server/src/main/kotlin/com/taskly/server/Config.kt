package com.taskly.server

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
    /** Per-IP bucket for the /v1/auth endpoints (password guessing). */
    val authTokensPerSecond: Double = 0.2,
    val authBurstSize: Int = 10,
)

// Loader
object Config {
    private val config = ConfigFactory.load().getConfig("taskly")

    /** Built-in development secret from application.conf — never acceptable outside dev. */
    const val DEV_JWT_SECRET = "taskly-dev-secret-change-in-production-min-256bits!!"

    /** "dev" by default; set TASKLY_ENV=production (or anything else) in real deployments. */
    val env: String = config.getString("env")
    val isDev: Boolean get() = env.equals("dev", ignoreCase = true)

    val database = DatabaseConfig(
        host = config.getString("database.host"),
        port = config.getInt("database.port"),
        database = config.getString("database.database"),
        user = config.getString("database.user"),
        password = config.getString("database.password"),
        poolSize = config.getInt("database.poolSize")
    )

    val redis = RedisConfig(
        host = config.getString("redis.host"),
        port = config.getInt("redis.port")
    )

    val auth = AuthConfig(
        jwtSecret = config.getString("auth.jwtSecret"),
        accessTokenExpiryMinutes = config.getLong("auth.accessTokenExpiryMinutes"),
        refreshTokenExpiryDays = config.getInt("auth.refreshTokenExpiryDays")
    ).also { validateAuth(it, isDev) }

    val sync = SyncConfig(
        maxBatchSize = config.getInt("sync.maxBatchSize")
    )

    val rateLimit = RateLimitConfig(
        tokensPerSecond = config.getInt("rateLimit.tokensPerSecond"),
        burstSize = config.getInt("rateLimit.burstSize"),
        authTokensPerSecond = config.getDouble("rateLimit.authTokensPerSecond"),
        authBurstSize = config.getInt("rateLimit.authBurstSize"),
    )
}
/**
 * Fails fast when the JWT secret is unsafe for the current environment:
 * outside dev the built-in secret is rejected and a secret must be at least 32 chars.
 */
fun validateAuth(auth: AuthConfig, isDev: Boolean) {
    if (isDev) return
    require(auth.jwtSecret != Config.DEV_JWT_SECRET) {
        "JWT_SECRET must be set outside dev (TASKLY_ENV != dev); refusing to start with the built-in dev secret"
    }
    require(auth.jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
}
