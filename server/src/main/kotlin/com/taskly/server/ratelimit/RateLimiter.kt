package com.taskly.server.ratelimit

import com.taskly.server.RateLimitConfig
import com.taskly.server.redis.RedisService
import kotlinx.coroutines.delay

/**
 * Redis-based token bucket rate limiter.
 * Uses the LUA script in RedisService for atomic operations.
 */
object RateLimiter {
    private lateinit var config: RateLimitConfig

    fun init(config: RateLimitConfig) {
        this.config = config
    }

    /**
     * Check if a request is allowed. Returns true if the token was consumed.
     */
    fun check(key: String, tokens: Long = 1): Boolean {
        val nowSeconds = System.currentTimeMillis() / 1000
        return RedisService.tokenBucketCheck(
            bucketKey = "rate_limit:$key",
            tokens = tokens,
            refillRate = config.tokensPerSecond.toDouble(),
            capacity = config.burstSize.toLong(),
            nowSeconds = nowSeconds
        )
    }

    /**
     * Check and block until a token is available.
     * Retries with exponential backoff.
     */
    suspend fun blockUntilAvailable(key: String, tokens: Long = 1, maxWaitMs: Long = 30_000) {
        val start = System.currentTimeMillis()
        var waitMs = 50L

        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (check(key, tokens)) {
                return
            }
            delay(waitMs)
            waitMs = (waitMs * 1.5).toLong().coerceAtMost(1000)
        }

        throw RateLimitExceededException("Rate limit exceeded for key: $key")
    }
}

class RateLimitExceededException(message: String) : RuntimeException(message)