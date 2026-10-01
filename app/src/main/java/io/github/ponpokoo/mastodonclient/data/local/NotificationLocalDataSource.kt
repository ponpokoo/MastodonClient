package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.withTransaction
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.CachedNotifications
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface NotificationLocalDataSource {
    suspend fun read(session: AccountSession): CachedNotifications
    suspend fun write(session: AccountSession, notifications: List<TimelineNotification>)
    suspend fun writeMarker(session: AccountSession, lastReadId: String?)
    suspend fun deleteAccount(sessionId: String)
}

class RoomNotificationLocalDataSource(
    private val database: BrowsingDatabase,
    private val isAccountPresent: suspend (AccountSession) -> Boolean,
) : NotificationLocalDataSource {
    private val dao = database.notifications()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(session: AccountSession): CachedNotifications = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction CachedNotifications()
            val instance = session.instanceUrl.trimEnd('/')
            CachedNotifications(
                notifications = dao.read(session.sessionId, instance).mapNotNull { row ->
                    try {
                        json.decodeFromString<TimelineNotification>(row.payload)
                    } catch (_: SerializationException) {
                        // An incompatible/corrupt cache row must not prevent a network refresh.
                        null
                    }
                },
                lastReadId = dao.readMarker(session.sessionId, instance),
            )
        }
    }

    override suspend fun write(session: AccountSession, notifications: List<TimelineNotification>) = withContext(Dispatchers.IO) {
        val instance = session.instanceUrl.trimEnd('/')
        val rows = notifications.distinctBy(TimelineNotification::id).take(MAX_NOTIFICATIONS)
            .mapIndexed { position, notification ->
                CachedNotificationEntity(session.sessionId, instance, notification.id, position, json.encodeToString(notification))
            }
        database.withTransaction {
            // Checked inside the same transaction that serializes account deletion. A late
            // response cannot repopulate the cache after logout has removed the session.
            if (!isAccountPresent(session)) return@withTransaction
            currentCoroutineContext().ensureActive()
            dao.clearRows(session.sessionId, instance)
            dao.insert(rows)
        }
    }

    override suspend fun writeMarker(session: AccountSession, lastReadId: String?) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction
            currentCoroutineContext().ensureActive()
            dao.writeMarker(NotificationMarkerEntity(session.sessionId, session.instanceUrl.trimEnd('/'), lastReadId))
        }
    }

    override suspend fun deleteAccount(sessionId: String) {
        database.withTransaction {
            dao.deleteNotifications(sessionId)
            dao.deleteMarker(sessionId)
        }
    }

    companion object {
        const val MAX_NOTIFICATIONS = 200
    }
}
