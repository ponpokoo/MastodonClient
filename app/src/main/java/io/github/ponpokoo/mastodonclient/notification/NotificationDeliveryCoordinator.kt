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
    suspend fun acknowledge(sessionId: String, notificationIds: Set<String>, dismiss: () -> Unit) = mutex.withLock {
        write(sessionId, (read(sessionId) + notificationIds).distinct().takeLast(500))
        dismiss()
    }
    suspend fun updateVisibleNotification(update: () -> Unit) = mutex.withLock { update() }
    private companion object { val mutex = Mutex() }
}
