package io.github.ponpokoo.mastodonclient.data.local

import androidx.room.withTransaction
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.CachedNotifications
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory
import io.github.ponpokoo.mastodonclient.domain.model.NotificationReadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface NotificationLocalDataSource {
    suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) = Unit
    suspend fun read(session: AccountSession): CachedNotifications
    suspend fun write(session: AccountSession, notifications: List<TimelineNotification>)
    suspend fun writeMarker(session: AccountSession, lastReadId: String?)
    suspend fun deleteAccount(sessionId: String)
    suspend fun readCategory(session: AccountSession, category: NotificationCategory): CachedNotifications =
        read(session).let { it.copy(notifications = it.notifications.filter(category::includes)) }
    suspend fun writeCategory(session: AccountSession, category: NotificationCategory, notifications: List<TimelineNotification>) {
        if (category == NotificationCategory.All) write(session, notifications)
    }
    suspend fun writeReadState(session: AccountSession, state: NotificationReadState) = Unit
}

class RoomNotificationLocalDataSource(
    private val database: BrowsingDatabase,
    private val isAccountPresent: suspend (AccountSession) -> Boolean,
) : NotificationLocalDataSource {
    private val dao = database.notifications()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var moderation = io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState()

    override suspend fun applyModeration(session: AccountSession, state: io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState) = withContext(Dispatchers.IO) {
        database.withTransaction {
            moderation = state
            if (!isAccountPresent(session)) return@withTransaction
            val instance = session.instanceUrl.trimEnd('/')
            val hidden = dao.read(session.sessionId, instance).filter { row ->
                val notification = try { json.decodeFromString<TimelineNotification>(row.payload) }
                    catch (_: SerializationException) { return@filter false }
                state.hides(session, notification)
            }
            dao.deleteRows(session.sessionId, instance, hidden.map { it.id })
            for (category in listOf(NotificationCategory.Mentions, NotificationCategory.Reactions)) {
                val payload = dao.readCategory(session.sessionId, instance, category.name) ?: continue
                val rows = decodeNotifications(payload).filterNot { state.hides(session, it) }
                dao.writeCategory(NotificationCategoryCacheEntity(session.sessionId, instance, category.name, json.encodeToString(rows)))
            }
        }
    }

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
                readState = decodeReadState(dao.readReadState(session.sessionId, instance)),
            )
        }
    }

    override suspend fun write(session: AccountSession, notifications: List<TimelineNotification>) = withContext(Dispatchers.IO) {
        val instance = session.instanceUrl.trimEnd('/')
        database.withTransaction {
            // Checked inside the same transaction that serializes account deletion. A late
            // response cannot repopulate the cache after logout has removed the session.
            if (!isAccountPresent(session)) return@withTransaction
            currentCoroutineContext().ensureActive()
            val rows = notifications.filterNot { moderation.hides(session, it) }
                .distinctBy(TimelineNotification::id).take(MAX_NOTIFICATIONS).mapIndexed { position, notification ->
                    CachedNotificationEntity(session.sessionId, instance, notification.id, position, json.encodeToString(notification))
                }
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

    override suspend fun readCategory(session: AccountSession, category: NotificationCategory): CachedNotifications {
        if (category == NotificationCategory.All) return read(session)
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                if (!isAccountPresent(session)) return@withTransaction CachedNotifications()
                val instance = session.instanceUrl.trimEnd('/')
                CachedNotifications(
                    notifications = decodeNotifications(dao.readCategory(session.sessionId, instance, category.name))
                        .filterNot { moderation.hides(session, it) },
                    lastReadId = dao.readMarker(session.sessionId, instance),
                    readState = decodeReadState(dao.readReadState(session.sessionId, instance)),
                )
            }
        }
    }

    override suspend fun writeCategory(session: AccountSession, category: NotificationCategory, notifications: List<TimelineNotification>) {
        if (category == NotificationCategory.All) { write(session, notifications); return }
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (!isAccountPresent(session)) return@withTransaction
                currentCoroutineContext().ensureActive()
                val rows = notifications.filter(category::includes).filterNot { moderation.hides(session, it) }
                    .distinctBy(TimelineNotification::id).take(MAX_NOTIFICATIONS)
                dao.writeCategory(NotificationCategoryCacheEntity(session.sessionId, session.instanceUrl.trimEnd('/'), category.name, json.encodeToString(rows)))
            }
        }
    }

    override suspend fun writeReadState(session: AccountSession, state: NotificationReadState) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!isAccountPresent(session)) return@withTransaction
            currentCoroutineContext().ensureActive()
            dao.writeReadState(NotificationReadStateEntity(session.sessionId, session.instanceUrl.trimEnd('/'),
                json.encodeToString(state.copy(viewedIds = state.viewedIds.distinct().take(1000)))))
        }
    }

    private fun decodeNotifications(payload: String?): List<TimelineNotification> = try {
        payload?.let { json.decodeFromString<List<TimelineNotification>>(it) }.orEmpty()
    } catch (_: SerializationException) { emptyList() }

    private fun decodeReadState(payload: String?): NotificationReadState = try {
        payload?.let { json.decodeFromString<NotificationReadState>(it) } ?: NotificationReadState()
    } catch (_: SerializationException) { NotificationReadState() }

    override suspend fun deleteAccount(sessionId: String) {
        database.withTransaction {
            dao.deleteNotifications(sessionId)
            dao.deleteMarker(sessionId)
            dao.deleteCategories(sessionId)
            dao.deleteReadState(sessionId)
        }
    }

    companion object {
        const val MAX_NOTIFICATIONS = 200
    }
}
