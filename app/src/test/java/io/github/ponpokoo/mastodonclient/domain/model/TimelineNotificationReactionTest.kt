package io.github.ponpokoo.mastodonclient.domain.model

import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineNotificationReactionTest {
    private val actor = StatusAuthor("actor", "Actor", "actor", "")

    @Test fun matchesTheNotificationActorWhenThePostHasDifferentReactions() {
        val ownReaction = EmojiReaction("👍", 1, false, null, setOf(actor.id))
        val otherReaction = EmojiReaction("🎉", 1, false, null, setOf("other"))
        val notification = notificationWith(ownReaction, otherReaction)

        assertEquals(ownReaction, notification.matchingReaction)
    }

    @Test fun doesNotGuessWhenTheActorIsMissingOrHasMultipleReactions() {
        val first = EmojiReaction("👍", 1, false, null, setOf(actor.id))
        val second = EmojiReaction("🎉", 1, false, null, setOf(actor.id))

        assertNull(notificationWith(EmojiReaction("👍", 1, false, null, emptySet())).matchingReaction)
        assertNull(notificationWith(first, second).matchingReaction)
    }

    @Test fun responseEmojiTakesPriorityOverChangingStatusAggregate() {
        val notification = notificationWith(EmojiReaction("👍", 1, false, null, setOf(actor.id)))
            .copy(type = "pleroma:emoji_reaction", reactionEmoji = "☕")
        assertEquals("☕", notification.matchingReaction?.name)
        assertEquals(true, NotificationCategory.Reactions.includes(notification))
    }

    @Test fun customEmojiUsesResponseUrlAndMissingDirectEmojiRetainsUniqueActorFallback() {
        val reaction = EmojiReaction("blob", 1, false, "https://example.org/old.png", setOf(actor.id))
        val notification = notificationWith(reaction).copy(reactionEmoji = ":blob:", reactionEmojiUrl = "https://example.org/new.png")
        assertEquals("https://example.org/new.png", notification.matchingReaction?.imageUrl)
        assertEquals(reaction.imageUrl, notification.copy(reactionEmojiUrl = null).matchingReaction?.imageUrl)
        assertEquals(reaction, notification.copy(reactionEmoji = " ", reactionEmojiUrl = null).matchingReaction)
        assertNull(notification.copy(status = null, reactionEmoji = null).matchingReaction)
    }

    @Test fun unknownNotificationTypeIsNotMistakenForAReaction() {
        assertEquals(false, NotificationCategory.Reactions.includes(notificationWith().copy(type = "future_reaction_request")))
    }

    private fun notificationWith(vararg reactions: EmojiReaction) = TimelineNotification(
        id = "notification",
        type = "emoji_reaction",
        createdAt = "2026-09-25T00:00:00Z",
        account = actor,
        status = testStatus().copy(reactions = reactions.toList()),
    )
}
