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
    val burstSize: Int
)

// Loader
object Config {
    private val config = ConfigFactory.load().getConfig("taskly")

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
    )

    val sync = SyncConfig(
        maxBatchSize = config.getInt("sync.maxBatchSize")
    )

    val rateLimit = RateLimitConfig(
        tokensPerSecond = config.getInt("rateLimit.tokensPerSecond"),
        burstSize = config.getInt("rateLimit.burstSize")
    )
}