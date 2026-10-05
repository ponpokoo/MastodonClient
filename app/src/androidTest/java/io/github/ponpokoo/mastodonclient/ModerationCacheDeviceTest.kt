package io.github.ponpokoo.mastodonclient

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ModerationCacheDeviceTest {
    @Test fun hiddenAuthorsBoostsAndNotificationsStayRemovedAfterLateWritesAndDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "moderation-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
        val session = AccountSession("one", "https://one.example/", "me", "me", "Me", "", "unused")
        val other = session.copy(sessionId = "two")
        val target = StatusAuthor("opaque-target", "Target", "target", "")
        val kept = StatusAuthor("kept", "Kept", "kept", "")
        fun post(id: String, author: StatusAuthor, boosted: StatusAuthor? = null) = TimelineStatus(
            id, id, "2026-10-05T00:00:00Z", author, boosted, "text", "", false,
            "public", null, 0, 0, 0, mediaAttachments = emptyList())
        val posts = listOf(post("target", target), post("target-boost", target, kept),
            post("boost-by-target", kept, target), post("kept", kept))
        val page = TimelinePage(posts, "kept", true)
        val notices = listOf(TimelineNotification("target", "follow", "", target, null),
            TimelineNotification("reference", "mention", "", kept, posts.first()),
            TimelineNotification("kept", "follow", "", kept, null))
        try {
            val home = RoomHomeTimelineLocalDataSource(db) { true }
            val notifications = RoomNotificationLocalDataSource(db) { true }
            for (account in listOf(session, other)) {
                home.writePage(account, null, page)
                notifications.write(account, notices)
                notifications.writeMarker(account, "opaque-marker")
            }
            val blocked = AccountModerationState().changed(session, target.id, AccountRelationship(blocking = true))
            home.applyModeration(session, blocked); notifications.applyModeration(session, blocked)
            // A response started before the block may still try to write its old snapshot.
            home.writePage(session, null, page); notifications.write(session, notices)
            assertEquals(listOf("kept"), home.readPage(session, null, 20)!!.statuses.map { it.timelineId })
            assertEquals(listOf("kept"), notifications.read(session).notifications.map { it.id })
            assertEquals(4, home.readPage(other, null, 20)!!.statuses.size)
            assertEquals(3, notifications.read(other).notifications.size)
            db.close()
            db = Room.databaseBuilder(context, BrowsingDatabase::class.java, name).build()
            val reopenedHome = RoomHomeTimelineLocalDataSource(db) { true }
            val reopenedNotifications = RoomNotificationLocalDataSource(db) { true }
            assertEquals(listOf("kept"), reopenedHome.readPage(session, null, 20)!!.statuses.map { it.timelineId })
            assertEquals(listOf("kept"), reopenedNotifications.read(session).notifications.map { it.id })
            assertEquals("opaque-marker", reopenedNotifications.read(session).lastReadId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun unmuteKeepsBlockAndOnlyFreshDataAfterUnblockRestoresRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowsingDatabase::class.java).build()
        val session = AccountSession("one", "https://one.example", "me", "me", "Me", "", "unused")
        val author = StatusAuthor("author", "Author", "author", "")
        val post = TimelineStatus("row", "post", "", author, null, "text", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        val page = TimelinePage(listOf(post), "row", true)
        try {
            val source = RoomHomeTimelineLocalDataSource(db) { true }
            source.writePage(session, null, page)
            source.applyModeration(session, AccountModerationState().changed(session, author.id, AccountRelationship(muting = true, blocking = true)))
            source.applyModeration(session, AccountModerationState().changed(session, author.id, AccountRelationship(blocking = true)))
            source.writePage(session, null, page)
            assertTrue(source.readPage(session, null, 20)!!.statuses.isEmpty())
            source.applyModeration(session, AccountModerationState().changed(session, author.id, AccountRelationship()))
            assertTrue(source.readPage(session, null, 20)!!.statuses.isEmpty())
            source.writePage(session, null, page)
            assertEquals(listOf("row"), source.readPage(session, null, 20)!!.statuses.map { it.timelineId })
        } finally { db.close() }
    }
}
