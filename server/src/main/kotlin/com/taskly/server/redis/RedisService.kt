package com.taskly.server.redis

import com.taskly.server.RedisConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.Jedis

/**
 * Redis service using Jedis connection pool.
 * Provides pub/sub and token bucket rate limiting.
 */
object RedisService {
    private var pool: JedisPool? = null
    @Volatile
    private var config: RedisConfig? = null

    @Volatile
    private var bucketSha: String? = null

    fun init(config: RedisConfig) {
        this.config = config
        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 64
            maxIdle = 16
            minIdle = 2
            testOnBorrow = true
            testOnReturn = true
            // Never wait forever for a connection: a request fails fast instead of hanging
            setMaxWait(java.time.Duration.ofSeconds(2))
        }
        pool = JedisPool(poolConfig, config.host, config.port, 2000)
    }

    fun getPool(): JedisPool = pool ?: throw IllegalStateException("Redis not initialized")

    /**
     * A dedicated connection outside the pool, for the long-lived subscription of
     * [RedisFanout] (a blocking subscribe must not occupy a pooled connection).
     */
    fun newConnection(): Jedis {
        val c = config ?: throw IllegalStateException("Redis not initialized")
        return Jedis(c.host, c.port, 2000, 0)
    }

    /**
     * Publish a message to a channel.
     */
    fun publish(channel: String, message: String): Long {
        return getPool().resource.use { jedis ->
            jedis.publish(channel, message)
        }
    }

    /**
     * Token bucket rate limiter using a LUA script.
     *
     * KEYS[1] = bucketKey
     * ARGV[1] = tokens requested (usually 1)
     * ARGV[2] = refillRate (tokens per second)
     * ARGV[3] = capacity (max tokens)
     * ARGV[4] = current timestamp in seconds
     *
     * Returns 1 if allowed, 0 if denied.
     */
    fun tokenBucketCheck(
        bucketKey: String,
        tokens: Long = 1,
        refillRate: Double,
        capacity: Long,
        nowSeconds: Long
    ): Boolean {
        val luaScript = """
            local key = KEYS[1]
            local tokens = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            
            local bucket = redis.call('hmget', key, 'tokens', 'last_refill')
            local currentTokens = tonumber(bucket[1]) or capacity
            local lastRefill = tonumber(bucket[2]) or now
            
            local elapsed = math.max(0, now - lastRefill)
            local refill = elapsed * refillRate
            currentTokens = math.min(capacity, currentTokens + refill)
            
            if currentTokens >= tokens then
                currentTokens = currentTokens - tokens
                redis.call('hmset', key, 'tokens', currentTokens, 'last_refill', now)
                redis.call('expire', key, math.ceil(capacity / refillRate) * 2)
                return 1
            else
                redis.call('hmset', key, 'tokens', currentTokens, 'last_refill', now)
                redis.call('expire', key, math.ceil(capacity / refillRate) * 2)
                return 0
            end
        """.trimIndent()

        val args = listOf(tokens.toString(), refillRate.toString(), capacity.toString(), nowSeconds.toString())
        return getPool().resource.use { jedis ->
            // The script is loaded once and then called by SHA; reloaded if Redis was flushed
            val sha = bucketSha ?: jedis.scriptLoad(luaScript).also { bucketSha = it }
            val result = try {
                jedis.evalsha(sha, listOf(bucketKey), args)
            } catch (e: redis.clients.jedis.exceptions.JedisNoScriptException) {
                bucketSha = jedis.scriptLoad(luaScript)
                jedis.evalsha(bucketSha, listOf(bucketKey), args)
            }
            result as Long == 1L
        }
    }

    /** Current integer value of [key], or 0 when absent. */
    fun getCount(key: String): Long =
        getPool().resource.use { it.get(key)?.toLongOrNull() ?: 0L }

    /** Atomically increments [key]; sets [ttlSeconds] on the first increment of a window. */
    fun incrementWithTtl(key: String, ttlSeconds: Long): Long =
        getPool().resource.use { jedis ->
            val n = jedis.incr(key)
            if (n == 1L) jedis.expire(key, ttlSeconds)
            n
        }

    fun delete(key: String) {
        getPool().resource.use { it.del(key) }
    }

    fun close() {
        RedisFanout.stop()
        pool?.close()
        pool = null
    }
}