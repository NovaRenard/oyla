package kz.oyla.server.util

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Per-process sliding-window limiter. A shared limiter (for example Redis) is required for multiple replicas. */
class ActivationRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 5,
    private val window: Duration = Duration.ofMinutes(1)
) {
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun allow(key: String): Boolean {
        val now = clock.instant()
        val queue = attempts.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.firstOrNull()?.plus(window)?.isAfter(now) == false) queue.removeFirst()
            if (queue.size >= maxAttempts) return false
            queue.addLast(now)
            return true
        }
    }
}
