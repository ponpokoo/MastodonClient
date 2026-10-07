package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.data.local.PushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import io.github.ponpokoo.mastodonclient.data.remote.PushSyncSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import io.github.ponpokoo.mastodonclient.domain.repository.PushSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.IOException

fun interface SyncedNotificationPresenter {
    suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: suspend () -> Boolean)
}

/** Work data contains identifiers only; the cursor and pending generations share the encrypted registration. */
class DefaultPushSyncRepository(
    private val sessions: suspend () -> List<AccountSession>,
    private val store: PushRegistrationStore,
    private val source: PushSyncSource,
    private val presenter: SyncedNotificationPresenter,
    private val schedule: suspend (sessionId: String, registrationId: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : PushSyncRepository {
    override suspend fun recover(sessionId: String?, force: Boolean): Unit = withContext(Dispatchers.IO) {
        var failure: Throwable? = null
        for (session in sessions().filter { sessionId == null || it.sessionId == sessionId }) {
            runCatchingCancellable {
                PushRegistrationGuard.mutex.withLock {
                    val record = store.read(session.sessionId) ?: return@withLock
                    if (!valid(session, record)) return@withLock
                    val pending = record.syncRequested > record.syncCompleted
                    if (!force && !pending && record.syncInitialized && now() - record.syncCheckedAt < CHECK_INTERVAL) return@withLock
                    val requested = if (pending && !force) record else record.copy(syncRequested = record.syncRequested + 1,
                        syncBootstrapNotify = record.syncBootstrapNotify || force)
                    store.write(session.sessionId, requested)
                    schedule(session.sessionId, record.registrationId)
                }
            }.onFailure { if (failure == null) failure = it }
        }
        failure?.let { throw it }
        Unit
    }

    suspend fun requestRegistration(registrationId: String): Boolean = withContext(Dispatchers.IO) {
        PushRegistrationGuard.mutex.withLock {
            val (session, record) = sessions().firstNotNullOfOrNull { session ->
                store.read(session.sessionId)?.takeIf { it.registrationId == registrationId && valid(session, it) }
                    ?.let { session to it }
            } ?: return@withLock false
            store.write(session.sessionId, record.copy(syncRequested = record.syncRequested + 1, syncBootstrapNotify = true))
            schedule(session.sessionId, registrationId)
            true
        }
    }

    /** Serialized by account Unique Work. A later queued worker is a no-op if its generation was already served. */
    suspend fun sync(sessionId: String, registrationId: String) = withContext(Dispatchers.IO) {
        val target = PushRegistrationGuard.mutex.withLock {
            val session = sessions().firstOrNull { it.sessionId == sessionId } ?: return@withLock null
            val record = store.read(sessionId) ?: return@withLock null
            if (record.registrationId != registrationId || record.credentialBinding != PushRegistrationGuard.binding(session) ||
                record.syncRequested <= record.syncCompleted) return@withLock null
            if (record.state == PushRegistrationState.REGISTERING) throw IOException("Push registration update is pending")
            record.takeIf { valid(session, it) }?.let { session to it }
        } ?: return@withContext
        val (session, snapshot) = target
        suspend fun currentRecord(): StoredPushRegistration? {
            val currentSession = sessions().firstOrNull { it.sessionId == sessionId }
            if (!session.hasSameCredentials(currentSession)) return null
            val record = store.read(sessionId) ?: return null
            if (record.registrationId != registrationId || record.credentialBinding != snapshot.credentialBinding ||
                record.relayIdentity != snapshot.relayIdentity || record.keys.publicKey != snapshot.keys.publicKey) return null
            if (record.state == PushRegistrationState.REGISTERING) throw IOException("Push registration update is pending")
            return record.takeIf { valid(session, it) }
        }
        val notifications = linkedMapOf<String, TimelineNotification>()
        val cursors = mutableSetOf<String>()
        var maxId: String? = null
        var newestId = snapshot.syncSinceId
        do {
            if (currentRecord() == null) return@withContext
            val page = source.page(session, snapshot.syncSinceId, maxId)
            if (currentRecord() == null) return@withContext
            if (maxId == null) newestId = page.firstOrNull()?.id ?: newestId
            val delta = page.takeWhile { it.id != snapshot.syncSinceId }
            delta.forEach { notifications.putIfAbsent(it.id, it) }
            // Legacy registrations have no lower bound: bootstrap one current page, never replay all history.
            if (!snapshot.syncInitialized || page.isEmpty() || delta.size != page.size) break
            val next = page.last().id
            check(next.isNotBlank() && cursors.add(next)) { "Notifications pagination did not advance" }
            maxId = next
        } while (true) // Do not treat a short page as EOF: older instances clamp the requested limit.

        val bootstrapNotify = currentRecord()?.syncBootstrapNotify ?: return@withContext
        if (snapshot.syncInitialized || bootstrapNotify) {
            for (notification in notifications.values.toList().asReversed()) {
                PushRegistrationGuard.mutex.withLock {
                    val record = currentRecord() ?: return@withContext
                    if (notification.id !in record.syncDeliveredIds && (record.notificationTypes == null || notification.type in record.notificationTypes)) {
                        presenter.show(session, notification) { currentRecord() != null }
                        // Keep in-flight receipts until the whole batch commits, beyond the shared ledger's 500 IDs.
                        val latest = currentRecord() ?: return@withContext
                        store.write(sessionId, latest.copy(syncDeliveredIds = latest.syncDeliveredIds + notification.id))
                    }
                }
            }
        }
        PushRegistrationGuard.mutex.withLock {
            val record = currentRecord() ?: return@withLock
            // A Push arriving during a silent legacy bootstrap must get a notifying bootstrap of its own.
            if (!snapshot.syncInitialized && !bootstrapNotify && record.syncBootstrapNotify) return@withLock
            store.write(sessionId, record.copy(syncInitialized = true, syncSinceId = newestId,
                syncCompleted = snapshot.syncRequested, syncCheckedAt = now(), syncDeliveredIds = emptyList(),
                syncBootstrapNotify = record.syncRequested > snapshot.syncRequested && record.syncBootstrapNotify))
        }
    }
    private fun valid(session: AccountSession, record: StoredPushRegistration) =
        record.state == PushRegistrationState.ACTIVE && record.endpoint != null && record.credentialBinding == PushRegistrationGuard.binding(session)

    private companion object { const val CHECK_INTERVAL = 15 * 60 * 1000L }
}
