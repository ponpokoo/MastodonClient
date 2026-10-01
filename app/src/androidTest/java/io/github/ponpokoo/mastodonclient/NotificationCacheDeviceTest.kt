package io.github.ponpokoo.mastodonclient

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.BrowsingDatabase
import io.github.ponpokoo.mastodonclient.data.local.CachedNotificationEntity
import io.github.ponpokoo.mastodonclient.data.local.RoomNotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotificationCacheDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = AccountSession("one", "https://one.example/", "me", "me", "Me", "", "unused")
    private val author = StatusAuthor("sender", "送り主", "sender@one.example", "https://one.example/avatar.png")
    private fun notification(id: String) = TimelineNotification(id, "mention", "2026-10-01T00:00:00Z", author,
        TimelineStatus(id, "post-$id", "2026-10-01T00:00:00Z", author, null, "<p>本文</p>", "CW", true,
            "private", null, 1, 2, 3, mediaAttachments = emptyList()))

    @Test fun boundedSnapshotAndIndependentMarkerSurviveDatabaseReopen() = runBlocking {
        val name = "notification-test-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
        try {
            var source = RoomNotificationLocalDataSource(database) { true }
            // Identical timestamps and nonnumeric IDs must retain caller/server order.
            val rows = (0..249).map { notification("opaque-$it") }
            source.writeMarker(account, "read-notification-outside-cache")
            source.write(account, rows.take(1) + rows)
            database.close()
            database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
            source = RoomNotificationLocalDataSource(database) { true }
            val restored = source.read(account.copy(instanceUrl = "https://one.example"))
            assertEquals(rows.take(200), restored.notifications)
            assertEquals("read-notification-outside-cache", restored.lastReadId)
            source.write(account, listOf(notification("replacement")))
            assertEquals(listOf(notification("replacement")), source.read(account).notifications)
            assertEquals(restored.lastReadId, source.read(account).lastReadId)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun accountAndInstanceAreIsolatedAndLateWritesAfterLogoutAreRejected() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        val second = account.copy(sessionId = "two")
        val otherInstance = account.copy(instanceUrl = "https://other.example/")
        val present = mutableSetOf(account, second, otherInstance)
        val source = RoomNotificationLocalDataSource(database) { it in present }
        try {
            for (session in present) {
                source.write(session, listOf(notification("same-id").copy(type = session.instanceUrl + session.sessionId)))
                source.writeMarker(session, session.sessionId)
            }
            assertEquals(account.instanceUrl + account.sessionId, source.read(account).notifications.single().type)
            assertEquals(otherInstance.instanceUrl + otherInstance.sessionId, source.read(otherInstance).notifications.single().type)
            present.removeAll { it.sessionId == account.sessionId }
            source.deleteAccount(account.sessionId)
            source.write(account, listOf(notification("late")))
            source.writeMarker(account, "late-marker")
            assertTrue(database.notifications().read(account.sessionId, "https://one.example").isEmpty())
            assertNull(database.notifications().readMarker(account.sessionId, "https://one.example"))
            assertTrue(database.notifications().read(account.sessionId, "https://other.example").isEmpty())
            assertEquals("two", source.read(second).lastReadId)
            assertEquals(1, source.read(second).notifications.size)
        } finally { database.close() }
    }

    @Test fun cancelledReplacementPreservesCommittedSnapshotAndCorruptRowsAreSkipped() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        var pause = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = RoomNotificationLocalDataSource(database) {
            if (pause) { entered.complete(Unit); release.await() }
            true
        }
        try {
            source.write(account, listOf(notification("kept")))
            pause = true
            val write = launch { source.write(account, listOf(notification("cancelled"))) }
            entered.await()
            write.cancel()
            write.join()
            pause = false
            database.notifications().insert(listOf(CachedNotificationEntity("one", "https://one.example", "corrupt", 1, "invalid-json")))
            assertEquals(listOf(notification("kept")), source.read(account).notifications)
        } finally { database.close() }
    }
}
