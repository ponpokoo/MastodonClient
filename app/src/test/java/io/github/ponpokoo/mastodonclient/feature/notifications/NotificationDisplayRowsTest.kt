package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.NotificationCategory
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NotificationDisplayRowsTest {
    private fun event(id: String, type: String = "favourite", actor: String = id, post: String = "post", emoji: String? = null, image: String? = null) =
        TimelineNotification(id, type, "2026-10-22T00:00:00Z", StatusAuthor(actor, actor, actor, ""), testStatus(post), emoji, image)

    @Test fun groupingKeepsNotificationOrderAndSeparatesPostsKindsAndEmoji() {
        val input = listOf(
            event("opaque-z", emoji = "☕", type = "emoji_reaction"),
            event("older-id"),
            event("a", type = "reblog"),
            event("larger-id", type = "pleroma:emoji_reaction", emoji = "☕"),
            event("different-emoji", type = "reaction", emoji = "👍"),
            event("other-post", post = "another"),
            event("old-favourite"),
        )
        val rows = NotificationRowGrouper().rows(input, grouped = true)
        assertEquals(listOf("opaque-z", "older-id", "a", "different-emoji", "other-post"), rows.map { it.latest.id })
        assertEquals(setOf("opaque-z", "larger-id"), rows.first().notificationIds)
        assertEquals(setOf("older-id", "old-favourite"), rows[1].notificationIds)
        assertEquals(input.toSet(), rows.flatMap { it.notifications }.toSet())
    }

    @Test fun newActorsAndOlderPagesDoNotReplaceTheFirstDisplayedActorOrStableKey() {
        val grouper = NotificationRowGrouper()
        val first = event("initial", actor = "first")
        val initial = grouper.rows(listOf(first), true).single()
        val next = grouper.rows(listOf(event("live", actor = "second"), first, event("paged", actor = "older")), true).single()
        assertEquals(initial.key, next.key)
        assertEquals(listOf("first", "second", "older"), next.accounts.map { it.id })
        assertEquals("live", next.latest.id)
        val refreshed = grouper.rows(listOf(event("updated-first", actor = "first"), first), true).single()
        assertEquals(listOf("first"), refreshed.accounts.map { it.id })
    }

    @Test fun repeatedActorIsCountedOnceButAllNotificationIdsRemainAvailableForReadState() {
        val rows = NotificationRowGrouper().rows(listOf(event("new", actor = "same"), event("old", actor = "same")), true)
        assertEquals(1, rows.single().accounts.size)
        assertEquals(setOf("new", "old"), visibleNotificationIds(rows, setOf(rows.single().key, "footer")))
        assertTrue(visibleNotificationIds(rows, setOf("footer")).isEmpty())
    }

    @Test fun individualRowsAndCategoryTabsKeepAllEventsEvenWhenAllTabIsGrouped() {
        val events = listOf(event("r1", type = "reaction", emoji = "☕"), event("r2", type = "reaction", emoji = "☕"), event("reply", type = "mention"))
        val grouper = NotificationRowGrouper()
        val individual = grouper.rows(events, false)
        assertEquals(events, individual.map { it.latest })
        assertTrue(individual.none { it.grouped })
        val grouped = grouper.rows(events, true)
        assertEquals(2, grouped.size)
        val reactions = notificationRowsForCategory(events, NotificationCategory.Reactions, grouped)
        assertEquals(listOf("r1", "r2"), reactions.map { it.latest.id })
        assertTrue(reactions.none { it.grouped })
        val mentions = notificationRowsForCategory(events, NotificationCategory.Mentions, grouped)
        assertEquals(listOf("reply"), mentions.map { it.latest.id })
    }

    @Test fun unknownEmojiMissingPostAndUnsupportedTypesStayIndividual() {
        val events = listOf(event("unknown1", type = "reaction"), event("unknown2", type = "reaction"),
            event("missing").copy(status = null), event("follow1", type = "follow"), event("follow2", type = "follow"))
        assertEquals(events, NotificationRowGrouper().rows(events, true).map { it.latest })
    }

    @Test fun customEmojiIdentityUsesImageAsWellAsShortcodeAndSupportsColonSyntax() {
        val events = listOf(event("a", type = "reaction", emoji = ":blob:", image = "https://a.example/blob.png"),
            event("b", type = "emoji_reaction", emoji = "blob", image = "https://a.example/blob.png"),
            event("c", type = "reaction", emoji = "blob", image = "https://b.example/blob.png"))
        val rows = NotificationRowGrouper().rows(events, true)
        assertEquals(listOf(setOf("a", "b"), setOf("c")), rows.map { it.notificationIds })
    }

    @Test fun groupRetainsAllActorsForCountsAndUsesLatestPostSnapshotWithoutMutatingInput() {
        val events = (0..6).map { event("event:$it", actor = "actor:$it") }
        val updated = events.first().copy(status = events.first().status!!.copy(contentHtml = "edited", favourited = true))
        val input = listOf(updated) + events.drop(1)
        val row = NotificationRowGrouper().rows(input, true).single()
        assertEquals(7, row.accounts.size)
        assertEquals(updated.status, row.latest.status)
        assertEquals(input, row.notifications)
        assertFalse(events.first().status!!.favourited)
    }

    @Test fun independentGroupersDoNotShareRepresentativesAcrossAccountSessions() {
        val firstSession = NotificationRowGrouper()
        firstSession.rows(listOf(event("same-id", actor = "one")), true)
        val next = listOf(event("live", actor = "two"), event("same-id", actor = "one"))
        assertEquals("one", firstSession.rows(next, true).single().accounts.first().id)
        assertEquals("two", NotificationRowGrouper().rows(next, true).single().accounts.first().id)
    }

    @Test fun relativeTimeHandlesOldNotificationsClockSkewAndInvalidServerDates() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals("12日前", notificationRelativeTime("2026-10-10T12:00:00Z", now))
        assertEquals("今", notificationRelativeTime("2026-10-22T12:01:00Z", now))
        assertEquals("", notificationRelativeTime("unknown", now))
    }
}
