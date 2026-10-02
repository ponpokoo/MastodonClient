package io.github.ponpokoo.mastodonclient

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.BrowsingDatabase
import io.github.ponpokoo.mastodonclient.data.local.RoomHomeTimelineLocalDataSource
import io.github.ponpokoo.mastodonclient.data.local.RoomNotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HomeTimelineCacheDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = AccountSession("one", "https://one.example/", "me", "me", "Me", "", "unused")
    private val author = StatusAuthor("sender", "送り主", "sender@one.example", "")
    private fun status(id: String) = TimelineStatus(id, "post-$id", "2026-10-02T00:00:00Z", author, null,
        "<p>本文</p>", "CW", true, "private", null, 1, 2, 3, mediaAttachments = emptyList())
    private fun page(rows: List<TimelineStatus>, end: Boolean = false) = TimelinePage(rows, rows.lastOrNull()?.timelineId, end)

    @Test fun boundedServerOrderAndBoostRowsSurviveReopenAndCacheBoundaryIsNotServerEnd() = runBlocking {
        val name = "home-test-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
        val rows = (0..539).map { status("opaque-$it").copy(statusId = "shared-original", boostedBy = author) }
        try {
            RoomHomeTimelineLocalDataSource(database) { true }.writePage(account, null, page(rows, end = true))
            database.close()
            database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val restored = mutableListOf<TimelineStatus>()
            var cursor: String? = null
            repeat(25) {
                val cached = source.readPage(account.copy(instanceUrl = "https://one.example"), cursor, 20)!!
                assertEquals(20, cached.statuses.size)
                assertFalse(cached.endReached)
                restored += cached.statuses
                cursor = cached.nextMaxId
            }
            assertEquals(rows.take(500), restored)
            assertNull(source.readPage(account, cursor, 20))
            assertEquals(rows.drop(49).take(20), source.readPage(account, null, 20, anchorId = "opaque-49")!!.statuses)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun overlappingPagesKeepKnownTailButMissingOverlapDoesNotSkipUnfetchedRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val old = (0..59).map { status("old-$it") }
            source.writePage(account, null, page(old))
            source.writePage(account, null, page(listOf(status("new")) + old.take(19)))
            assertEquals(old.drop(19).take(20), source.readPage(account, "old-18", 20)!!.statuses)
            val fresh = listOf(status("different-a"), status("different-b"))
            source.writePage(account, "old-18", page(fresh))
            assertEquals(fresh, source.readPage(account, "old-18", 20)!!.statuses)
            assertNull(source.readPage(account, "different-b", 20))
            source.writePage(account, "outside-cache", page(listOf(status("outside"))))
            assertEquals("new", source.readPage(account, null, 20)!!.statuses.first().timelineId)
            source.writePage(account, null, page(listOf(status("unconnected"))))
            assertEquals(listOf(status("unconnected")), source.readPage(account, null, 20)!!.statuses)
            source.writePage(account, "unconnected", page(emptyList(), end = true))
            assertTrue(source.readPage(account, null, 20)!!.endReached)
        } finally { database.close() }
    }

    @Test fun actionsEditsStreamingAndDeletionPersistWithoutLosingBoostIdentity() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val original = status("original")
            val boost = original.copy(timelineId = "boost", boostedBy = author, createdAt = "boost-time")
            source.writePage(account, null, page(listOf(boost, original)))
            source.applyChange(account, BrowsingSession.Change.StatusUpdated(original.copy(favourited = true, favouritesCount = 4)))
            assertTrue(source.readPage(account, null, 20)!!.statuses.all { it.favourited })
            val votedPoll = io.github.ponpokoo.mastodonclient.domain.model.StatusPoll("poll", null, false, false, 1, 1, true, setOf(0),
                listOf(io.github.ponpokoo.mastodonclient.domain.model.PollOption("yes", 1)))
            source.applyChange(account, BrowsingSession.Change.StatusUpdated(original.copy(poll = votedPoll)))
            assertTrue(source.readPage(account, null, 20)!!.statuses.all { it.poll == votedPoll })
            source.applyChange(account, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(
                original.copy(contentHtml = "edited"), isEdit = true)))
            val restoredBoost = source.readPage(account, null, 20)!!.statuses.first()
            assertEquals("boost", restoredBoost.timelineId)
            assertEquals("boost-time", restoredBoost.createdAt)
            assertEquals(author, restoredBoost.boostedBy)
            assertEquals("edited", restoredBoost.contentHtml)
            source.applyChange(account, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("live"))))
            source.applyChange(account, BrowsingSession.Change.StatusDeleted(original.statusId))
            assertEquals(listOf(status("live")), source.readPage(account, null, 20)!!.statuses)
        } finally { database.close() }
    }

    @Test fun accountsInstancesAndNotificationsAreIndependentAndLateLogoutWritesAreRejected() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        val second = account.copy(sessionId = "two")
        val otherInstance = account.copy(instanceUrl = "https://other.example")
        val present = mutableSetOf(account, second, otherInstance)
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { it in present }
            val notifications = RoomNotificationLocalDataSource(database) { true }
            notifications.writeMarker(account, "notification-marker")
            for (session in present) source.writePage(session, null, page(listOf(status(session.sessionId + session.instanceUrl))))
            present.removeAll { it.sessionId == account.sessionId }
            source.deleteAccount(account.sessionId)
            source.writePage(account, null, page(listOf(status("late"))))
            source.applyChange(account, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("late-stream"))))
            assertTrue(database.homeTimeline().readAll(account.sessionId, "https://one.example").isEmpty())
            assertTrue(database.homeTimeline().readAll(account.sessionId, "https://other.example").isEmpty())
            assertEquals(1, source.readPage(second, null, 20)!!.statuses.size)
            assertEquals("notification-marker", notifications.read(account).lastReadId)
        } finally { database.close() }
    }

    @Test fun cancellationRollsBackPageReplacementAndCorruptPageFallsBackToNetwork() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        var pause = false
        val entered = CompletableDeferred<Unit>()
        val source = RoomHomeTimelineLocalDataSource(database) {
            if (pause) { entered.complete(Unit); CompletableDeferred<Unit>().await() }
            true
        }
        try {
            source.writePage(account, null, page(listOf(status("committed"))))
            pause = true
            val job = launch { source.writePage(account, null, page(listOf(status("cancelled")))) }
            entered.await()
            job.cancel(); job.join()
            pause = false
            assertEquals("committed", source.readPage(account, null, 20)!!.statuses.single().timelineId)
            database.openHelper.writableDatabase.execSQL("UPDATE home_timeline_cache SET payload = 'bad' WHERE id = 'committed'")
            assertNull(source.readPage(account, null, 20))
        } finally { database.close() }
    }

    @Test fun responseAndBufferedActionsCommitTogetherBeforeLaterAction() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        var pause = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = RoomHomeTimelineLocalDataSource(database) {
            if (pause) { entered.complete(Unit); release.await() }
            true
        }
        try {
            val original = status("post")
            source.writePage(account, null, page(listOf(original)))
            val buffered = BrowsingSession.Change.StatusUpdated(original.copy(favourited = true, favouritesCount = 4))
            pause = true
            val response = launch { source.writePage(account, null, page(listOf(original)), listOf(buffered)) }
            entered.await()
            val later = launch { source.applyChange(account, BrowsingSession.Change.StatusUpdated(original.copy(favourited = false, favouritesCount = 5))) }
            pause = false
            release.complete(Unit)
            response.join(); later.join()
            val restored = source.readPage(account, null, 20)!!.statuses.single()
            assertFalse(restored.favourited)
            assertEquals(5L, restored.favouritesCount)
        } finally { database.close() }
    }

    @Test fun versionOneMigrationPreservesNotificationsAndMarkerAndAddsHomeStorage() = runBlocking {
        val name = "home-migration-${UUID.randomUUID()}.db"
        val notification = TimelineNotification("notification", "mention", "2026-10-02", author, status("post"))
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE notification_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, id))")
            old.execSQL("CREATE TABLE notification_markers (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, lastReadId TEXT, PRIMARY KEY(sessionId, instanceUrl))")
            old.execSQL("INSERT INTO notification_cache VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(account.sessionId, "https://one.example", notification.id, 0, Json.encodeToString(notification)))
            old.execSQL("INSERT INTO notification_markers VALUES (?, ?, ?)", arrayOf(account.sessionId, "https://one.example", "marker"))
            old.version = 1
        }
        val database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).addMigrations(BrowsingDatabase.MIGRATION_1_2).build()
        try {
            val restored = RoomNotificationLocalDataSource(database) { true }.read(account)
            assertEquals(listOf(notification), restored.notifications)
            assertEquals("marker", restored.lastReadId)
            val home = RoomHomeTimelineLocalDataSource(database) { true }
            home.writePage(account, null, page(listOf(status("home"))))
            assertEquals("home", home.readPage(account, null, 20)!!.statuses.single().timelineId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
