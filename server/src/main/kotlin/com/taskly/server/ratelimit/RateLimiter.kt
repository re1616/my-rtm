package com.taskly.server.ratelimit

import com.taskly.server.RateLimitConfig
import com.taskly.server.errors.TooManyRequestsException
import com.taskly.server.redis.RedisService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Redis-based token bucket rate limiter (SPEC F-8).
 * Uses the Lua script in RedisService for atomic operations.
 */
object RateLimiter {
    private val logger = LoggerFactory.getLogger("RateLimiter")
    private lateinit var config: RateLimitConfig

    fun init(config: RateLimitConfig) {
        this.config = config
    }

    fun isInitialized(): Boolean = ::config.isInitialized

    /**
     * Check if a request is allowed. Returns true if the token was consumed.
     */
    fun check(key: String, tokens: Long = 1): Boolean =
        check(key, config.tokensPerSecond.toDouble(), config.burstSize.toLong(), tokens)

    /** Smaller per-IP bucket for credential endpoints. */
    fun checkAuth(key: String): Boolean =
        check(key, config.authTokensPerSecond, config.authBurstSize.toLong())

    fun check(key: String, refillPerSecond: Double, capacity: Long, tokens: Long = 1): Boolean {
        val nowSeconds = System.currentTimeMillis() / 1000
        return RedisService.tokenBucketCheck(
            bucketKey = "rate_limit:$key",
            tokens = tokens,
            refillRate = refillPerSecond,
            capacity = capacity,
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
     * Which bucket a request falls into: credential endpoints per client IP, everything else
     * per access token (i.e. per session) or per IP for anonymous calls. Health is not limited.
     */
    fun bucketFor(call: ApplicationCall): Pair<String, Boolean>? {
        val path = call.request.path()
        if (path == "/health") return null
        val ip = call.request.origin.remoteAddress
        if (path.startsWith("/v1/auth/")) return "auth:$ip" to true
        val auth = call.request.headers[HttpHeaders.Authorization]
        val who = if (auth != null && auth.startsWith("Bearer ")) "tok:" + sha256(auth.removePrefix("Bearer ")) else "ip:$ip"
        return "api:$who" to false
    }

    internal fun logFailOpen(e: Exception) {
        logger.warn("Rate limiter unavailable, allowing request: {}", e.message)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

/**
 * Applies [RateLimiter] to every request. When Redis is unavailable the limiter fails open
 * (the API keeps working; the outage is logged).
 */
val RateLimitPlugin = createApplicationPlugin("RateLimit") {
    onCall { call ->
        if (!RateLimiter.isInitialized()) return@onCall
        val (key, isAuth) = RateLimiter.bucketFor(call) ?: return@onCall
        val allowed = try {
            if (isAuth) RateLimiter.checkAuth(key) else RateLimiter.check(key)
        } catch (e: Exception) {
            RateLimiter.logFailOpen(e)
            true
        }
        if (!allowed) {
            throw TooManyRequestsException("Rate limit exceeded", retryAfterSeconds = if (isAuth) 5 else 1)
        }
    }
}

class RateLimitExceededException(message: String) : RuntimeException(message)
