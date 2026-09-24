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

    private fun notificationWith(vararg reactions: EmojiReaction) = TimelineNotification(
        id = "notification",
        type = "emoji_reaction",
        createdAt = "2026-09-25T00:00:00Z",
        account = actor,
        status = testStatus().copy(reactions = reactions.toList()),
    )
}
