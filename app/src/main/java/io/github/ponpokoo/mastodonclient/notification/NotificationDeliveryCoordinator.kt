package io.github.ponpokoo.mastodonclient.notification

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All notification transports use the same persisted session/notification-ID ledger. */
class NotificationDeliveryCoordinator(
    private val read: (String) -> List<String>,
    private val write: (String, List<String>) -> Unit,
) {
    suspend fun deliver(sessionId: String, notificationId: String, isCurrent: suspend () -> Boolean, post: () -> Boolean): Boolean = mutex.withLock {
        if (!isCurrent()) return@withLock false
        val seen = read(sessionId)
        if (notificationId in seen || !post()) return@withLock false
        write(sessionId, seen.takeLast(499) + notificationId)
        true
    }
    suspend fun acknowledge(sessionId: String, notificationIds: Set<String>, isCurrent: suspend () -> Boolean = { true }, dismiss: () -> Unit) = mutex.withLock {
        if (!isCurrent()) return@withLock
        write(sessionId, (read(sessionId) + notificationIds).distinct().takeLast(500))
        dismiss()
    }
    suspend fun updateVisibleNotification(update: suspend () -> Unit) = mutex.withLock { update() }
    suspend fun removeAccount(remove: () -> Unit) = mutex.withLock { remove() }
    suspend fun updateIfCurrent(isCurrent: suspend () -> Boolean, update: () -> Unit) = mutex.withLock {
        if (isCurrent()) update()
    }
    private companion object { val mutex = Mutex() }
}
