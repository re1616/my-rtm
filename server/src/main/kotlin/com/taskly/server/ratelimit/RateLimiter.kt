package com.taskly.server.ratelimit

import com.taskly.server.RateLimitConfig
import com.taskly.server.redis.RedisService
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Redis-based token bucket rate limiter.
 * Uses the LUA script in RedisService for atomic operations.
 *
 * SEC-6: per-IP and per-account rate limits for /v1/auth.
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

    /**
     * SEC-6: Per-account rate limit for failed auth attempts.
     * Tracks failed attempts in Redis with TTL. Returns true if limit exceeded.
     */
    fun checkAccountLimit(accountKey: String, maxAttempts: Int? = null): Boolean {
        val limit = maxAttempts ?: config.perAccountMaxAttempts
        val redisKey = "rate_limit:account:$accountKey"
        val current = RedisService.getPool().resource.use { jedis ->
            jedis.get(redisKey)?.toLongOrNull() ?: 0L
        }
        return current >= limit
    }

    /**
     * SEC-6: Record a failed auth attempt for an account.
     * Returns the current attempt count.
     */
    fun recordFailedAttempt(accountKey: String): Long {
        val redisKey = "rate_limit:account:$accountKey"
        return RedisService.getPool().resource.use { jedis ->
            val count = jedis.incr(redisKey)
            if (count == 1L) {
                jedis.expire(redisKey, 900) // 15 minutes TTL
            }
            count
        }
    }

    /**
     * SEC-6: Clear failed attempts on successful auth.
     */
    fun clearAccountLimit(accountKey: String) {
        val redisKey = "rate_limit:account:$accountKey"
        RedisService.getPool().resource.use { jedis ->
            jedis.del(redisKey)
        }
    }
}

class RateLimitExceededException(message: String) : RuntimeException(message)