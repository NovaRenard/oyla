package kz.oyla.server.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates session transitions and board mutations inside one server process. */
class SessionLockRegistry {
    private val locks = ConcurrentHashMap<UUID, Mutex>()
    suspend fun <T> withLock(sessionId: UUID, block: suspend () -> T): T =
        locks.computeIfAbsent(sessionId) { Mutex() }.withLock { block() }
}
