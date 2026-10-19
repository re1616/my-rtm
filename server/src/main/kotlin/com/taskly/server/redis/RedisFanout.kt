package com.taskly.server.redis

import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Redis subscription per server instance for all change notifications (`sync:*`),
 * dispatched in memory to the sockets that listen to a channel.
 *
 * Before this, every WebSocket held its own blocking subscription — a pooled Redis
 * connection and a thread each — so about 30 open sockets exhausted the pool and every
 * request that touched Redis (rate limiting) hung. Found by the QA-6 load test.
 */
object RedisFanout {
    private val log = LoggerFactory.getLogger("RedisFanout")
    const val PATTERN = "sync:*"

    private val listeners = ConcurrentHashMap<String, MutableSet<(String) -> Unit>>()
    private val generation = AtomicInteger()
    @Volatile private var thread: Thread? = null
    @Volatile private var pubSub: JedisPubSub? = null
    @Volatile private var ready = CountDownLatch(1)

    /** Delivers messages published to [channel] to [handler] until the returned handle is closed. */
    fun listen(channel: String, handler: (String) -> Unit): AutoCloseable {
        startIfNeeded()
        listeners.computeIfAbsent(channel) { ConcurrentHashMap.newKeySet() }.add(handler)
        return AutoCloseable {
            listeners.computeIfPresent(channel) { _, set -> set.remove(handler); if (set.isEmpty()) null else set }
        }
    }

    /** Number of channels with at least one listener (for tests and metrics). */
    fun channelCount(): Int = listeners.size

    /** Starts the subscriber thread once and waits (up to 5 s) until Redis confirms it. */
    fun ensureStarted() {
        val latch = startIfNeeded()
        if (!latch.await(5, TimeUnit.SECONDS)) log.warn("Redis subscription not confirmed within 5 s")
    }

    /** Starts the subscriber thread if it is not running; does not wait. */
    private fun startIfNeeded(): CountDownLatch =
        synchronized(this) {
            if (thread?.isAlive != true) {
                val gen = generation.incrementAndGet()
                val l = CountDownLatch(1)
                ready = l
                thread = Thread({ loop(gen, l) }, "redis-fanout-$gen").apply { isDaemon = true; start() }
            }
            ready
        }

    private fun loop(gen: Int, latch: CountDownLatch) {
        var backoff = 500L
        while (generation.get() == gen) {
            try {
                RedisService.newConnection().use { jedis ->
                    val ps = object : JedisPubSub() {
                        override fun onPSubscribe(pattern: String, subscribedChannels: Int) {
                            backoff = 500L
                            latch.countDown()
                        }

                        override fun onPMessage(pattern: String, channel: String, message: String) {
                            listeners[channel]?.forEach { h ->
                                try {
                                    h(message)
                                } catch (e: Exception) {
                                    log.debug("Listener failed: ${e.message}")
                                }
                            }
                        }
                    }
                    pubSub = ps
                    jedis.psubscribe(ps, PATTERN) // blocks until unsubscribed or the connection drops
                }
            } catch (e: Exception) {
                if (generation.get() == gen) log.warn("Redis subscription lost: ${e.message}; reconnecting in $backoff ms")
            }
            if (generation.get() != gen) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = minOf(backoff * 2, 10_000L)
        }
    }

    /** Stops the subscriber (called when Redis is closed); the next [listen] starts it again. */
    fun stop() {
        synchronized(this) {
            generation.incrementAndGet()
            val t = thread
            thread = null
            try {
                if (pubSub?.isSubscribed == true) pubSub?.punsubscribe()
            } catch (e: Exception) {
                log.debug("punsubscribe failed: ${e.message}")
            }
            t?.interrupt()
            ready = CountDownLatch(1)
        }
        listeners.clear()
    }
}
