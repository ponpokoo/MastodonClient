package io.github.ponpokoo.mastodonclient

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.BrowsingDatabase
import io.github.ponpokoo.mastodonclient.data.local.RoomHomeTimelineLocalDataSource
import io.github.ponpokoo.mastodonclient.data.local.RoomNotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineWindowRecord
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.mergeHomePage
import io.github.ponpokoo.mastodonclient.domain.session.fillHomeGap
import io.github.ponpokoo.mastodonclient.domain.session.homeIndex
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

    @Test fun findsBoostOnlyByOriginalIdAndRestoresActionsForAllRowsInTheSameAccount() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val other = account.copy(sessionId = "two")
            val original = status("original").copy(statusId = "original")
            val boost = original.copy(timelineId = "boost", boostedBy = author)
            source.writePage(account, null, page(listOf(boost)))
            source.writePage(other, null, page(listOf(original)))
            assertEquals(boost, source.readStatus(account, original.statusId))
            assertNull(source.readStatus(account, boost.timelineId))
            assertNull(source.readStatus(account.copy(instanceUrl = "https://other.example"), original.statusId))

            source.writeWindow(account, page(listOf(boost, original)), "boost", emptyList())
            source.applyStatusUpdates(account, listOf(
                BrowsingSession.Change.StatusUpdated(original.copy(favourited = true, favouritesCount = 8)),
            ))
            val restored = source.readWindow(account)!!
            assertEquals(listOf("boost", "original"), restored.statuses.map { it.timelineId })
            assertEquals(author, restored.statuses.first().boostedBy)
            assertEquals(listOf(8L, 8L), restored.statuses.map { it.favouritesCount })
            assertTrue(restored.statuses.all { it.favourited })
            assertEquals(original, source.readStatus(other, original.statusId))
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

    @Test fun indexedUpdatesHandleLargeBatchesAndApplyRepeatedUpdatesInOrder() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val original = status("original")
            val boost = original.copy(timelineId = "boost", boostedBy = author)
            source.writePage(account, null, page(listOf(boost, original)))
            val unrelated = List(1_001) { BrowsingSession.Change.StatusUpdated(status("missing-$it")) }
            source.applyStatusUpdates(account, unrelated + listOf(
                BrowsingSession.Change.StatusUpdated(original.copy(favouritesCount = 1)),
                BrowsingSession.Change.StatusUpdated(original.copy(favouritesCount = 2)),
            ))
            assertEquals(listOf(boost.copy(favouritesCount = 2), original.copy(favouritesCount = 2)),
                source.readPage(account, null, 20)!!.statuses)
        } finally { database.close() }
    }

    @Test fun streamingDeletesOneBoostByRowIdAndAllOtherCopiesByOriginalId() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val original = status("original").copy(statusId = "original")
            val boosts = List(3) { original.copy(timelineId = "boost-$it", boostedBy = author) }
            val survivors = List(4) { status("survivor-$it") }
            source.writePage(account, null, page(listOf(boosts[0], survivors[0], original, survivors[1],
                boosts[1], survivors[2], boosts[2], survivors[3]), end = true))
            source.applyChange(account, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusDeleted(boosts[1].timelineId)))
            assertEquals(original, source.readStatus(account, original.statusId))
            assertEquals(listOf(boosts[0], boosts[2]), source.readPage(account, null, 20)!!.statuses.filter { it.boostedBy != null })
            source.applyChange(account, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusDeleted(original.statusId)))
            assertEquals(page(survivors.drop(1), end = true), source.readPage(account, survivors.first().timelineId, 20))
            assertEquals(survivors.indices.toList(), database.homeTimeline().readAll(account.sessionId, "https://one.example")
                .map { it.position })
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
        val database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name)
            .addMigrations(BrowsingDatabase.MIGRATION_1_2, BrowsingDatabase.MIGRATION_2_3,
                BrowsingDatabase.MIGRATION_3_4, BrowsingDatabase.MIGRATION_4_5).build()
        try {
            val restored = RoomNotificationLocalDataSource(database) { true }.read(account)
            assertEquals(listOf(notification), restored.notifications)
            assertEquals("marker", restored.lastReadId)
            val home = RoomHomeTimelineLocalDataSource(database) { true }
            home.writePage(account, null, page(listOf(status("home"))))
            assertEquals("home", home.readPage(account, null, 20)!!.statuses.single().timelineId)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun disconnectedWindowsSurviveReopenAndPageReadsDoNotCrossGap() = runBlocking {
        val name = "home-gap-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
        try {
            val old = page(listOf(status("old"), status("older")))
            val window = old.mergeHomePage(page(listOf(status("new"))))
            RoomHomeTimelineLocalDataSource(database) { true }.writeWindow(account, window, "old", emptyList())
            database.close()
            database = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val restored = source.readWindow(account)!!
            assertEquals(listOf("new", "old", "older"), restored.statuses.map { it.timelineId })
            assertEquals(window.homeIndex().gaps, restored.homeIndex().gaps)
            assertEquals(listOf(status("new")), source.readPage(account, null, 20)!!.statuses)
            assertNull(source.readPage(account, "new", 20))
            val joined = restored.fillHomeGap(restored.homeIndex().gaps.single(), page(listOf(status("middle"), status("old"))))
            source.writeWindow(account, joined, "old", emptyList())
            assertTrue(source.readWindow(account)!!.homeIndex().gaps.isEmpty())
            source.deleteAccount(account.sessionId)
            assertNull(source.readWindow(account))
            assertNull(database.homeTimeline().window(account.sessionId, "https://one.example"))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun windowBudgetKeepsAnchorAndDeletionDoesNotLoseGapCursor() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val window = page((0..599).map { status("row-$it") }).copy(homeIndex =
                io.github.ponpokoo.mastodonclient.domain.model.HomeTimelineIndex((0..599).map { "row-$it" }))
            source.writeWindow(account, window, "row-550", emptyList())
            val bounded = source.readWindow(account)!!
            assertTrue(bounded.statuses.size <= 500)
            assertTrue(bounded.statuses.any { it.timelineId == "row-550" })
            val gap = bounded.homeIndex().gaps.single()
            source.applyChange(account, BrowsingSession.Change.StatusDeleted("post-${gap.maxId}"))
            assertEquals(gap, source.readWindow(account)!!.homeIndex().gaps.single())
            assertFalse(source.readWindow(account)!!.statuses.any { it.timelineId == gap.maxId })
        } finally { database.close() }
    }

    @Test fun versionThreeMigrationPreservesHomeAndNotificationMarker() = runBlocking {
        val name = "home-v3-${UUID.randomUUID()}.db"
        val database = openLegacyHome(name, version = 3)
        try {
            assertEquals(listOf(status("existing")), RoomHomeTimelineLocalDataSource(database) { true }.readWindow(account)!!.statuses)
            assertEquals("marker", RoomNotificationLocalDataSource(database) { true }.read(account).lastReadId)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun versionFourBackfillsBoostsAndPreservesWindowAndCorruptPageRecovery() = runBlocking {
        val name = "home-v4-${UUID.randomUUID()}.db"
        val original = status("existing")
        val boost = original.copy(timelineId = "boost", boostedBy = author)
        val window = page(listOf(boost)).mergeHomePage(page(listOf(original)))
        val record = HomeTimelineWindowRecord(
            window.homeIndex(), window.nextMaxId, window.endReached, "boost")
        val database = openLegacyHome(name, version = 4) { old ->
            old.execSQL("INSERT INTO home_timeline_cache VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(account.sessionId, "https://one.example", boost.timelineId, 1, Json.encodeToString(boost)))
            old.execSQL("INSERT INTO home_timeline_windows VALUES (?, ?, ?)",
                arrayOf(account.sessionId, "https://one.example", Json.encodeToString(record)))
            old.execSQL("INSERT INTO home_timeline_cache VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>("corrupt", "https://one.example", "damaged", 0, "bad"))
            old.execSQL("INSERT INTO home_timeline_state VALUES (?, ?, 0)", arrayOf("corrupt", "https://one.example"))
        }
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            assertEquals(original, source.readStatus(account, original.statusId))
            assertEquals(window.homeIndex().gaps, source.readWindow(account)!!.homeIndex().gaps)
            assertEquals(record, Json.decodeFromString<HomeTimelineWindowRecord>(
                database.homeTimeline().window(account.sessionId, "https://one.example")!!.payload))
            source.applyStatusUpdates(account, listOf(BrowsingSession.Change.StatusUpdated(original.copy(favourited = true))))
            assertTrue(source.readWindow(account)!!.statuses.all { it.favourited })
            val corrupt = account.copy(sessionId = "corrupt")
            assertNull(source.readPage(corrupt, null, 20))
            source.writePage(corrupt, null, page(listOf(status("recovered"))))
            assertEquals(listOf("recovered"), source.readWindow(corrupt)!!.statuses.map { it.timelineId })
            assertEquals("marker", RoomNotificationLocalDataSource(database) { true }.read(account).lastReadId)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun identicalSaveSkipsWritesAndAnchorChangeOnlyWritesWindow() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        try {
            val source = RoomHomeTimelineLocalDataSource(database) { true }
            val window = page(listOf(status("new"), status("old")))
            source.writeWindow(account, window, "old", emptyList())
            val sql = database.openHelper.writableDatabase
            for (table in listOf("home_timeline_cache", "home_timeline_state")) {
                for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                    sql.execSQL("CREATE TRIGGER forbid_${table}_$operation BEFORE $operation ON $table " +
                        "BEGIN SELECT RAISE(ABORT, 'unexpected write'); END")
                }
            }
            sql.execSQL("CREATE TRIGGER forbid_window_insert BEFORE INSERT ON home_timeline_windows " +
                "BEGIN SELECT RAISE(ABORT, 'unexpected window write'); END")
            source.writeWindow(account, window, "old", emptyList())
            sql.execSQL("DROP TRIGGER forbid_window_insert")
            source.writeWindow(account, window, "new", emptyList())
            val record = Json.decodeFromString<HomeTimelineWindowRecord>(
                database.homeTimeline().window(account.sessionId, "https://one.example")!!.payload)
            assertEquals("new", record.anchorId)
        } finally { database.close() }
    }

    private fun openLegacyHome(name: String, version: Int, addRows: (SQLiteDatabase) -> Unit = {}): BrowsingDatabase {
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE notification_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, id))")
            old.execSQL("CREATE TABLE notification_markers (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, lastReadId TEXT, PRIMARY KEY(sessionId, instanceUrl))")
            old.execSQL("CREATE TABLE home_timeline_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, id))")
            old.execSQL("CREATE TABLE home_timeline_state (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, endReached INTEGER NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            old.execSQL("CREATE TABLE notification_category_cache (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, category TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl, category))")
            old.execSQL("CREATE TABLE notification_read_state (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            if (version == 4) {
                old.execSQL("CREATE TABLE home_timeline_windows (sessionId TEXT NOT NULL, instanceUrl TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(sessionId, instanceUrl))")
            }
            val existing = status("existing")
            old.execSQL("INSERT INTO home_timeline_cache VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(account.sessionId, "https://one.example", existing.timelineId, 0, Json.encodeToString(existing)))
            old.execSQL("INSERT INTO home_timeline_state VALUES (?, ?, 0)", arrayOf(account.sessionId, "https://one.example"))
            old.execSQL("INSERT INTO notification_markers VALUES (?, ?, ?)", arrayOf(account.sessionId, "https://one.example", "marker"))
            addRows(old)
            old.version = version
        }
        return Room.databaseBuilder(context, BrowsingDatabase::class.java, name)
            .addMigrations(BrowsingDatabase.MIGRATION_3_4, BrowsingDatabase.MIGRATION_4_5).build()
    }
}
