package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.remote.dto.NotificationDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.StatusDto
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReactionNotificationMappingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val author = """{"id":"actor","username":"actor","acct":"actor"}"""
    private fun status(extra: String) = """{"id":"post","created_at":"now","account":$author,$extra}"""

    @Test fun notificationEmojiAndCustomUrlSurviveDomainAndCacheSerialization() {
        for ((emoji, image) in listOf("☕" to null, ":blob:" to "https://example.org/blob.png")) {
            val source = """{"id":"opaque","type":"pleroma:emoji_reaction","created_at":"now","account":$author,
                "emoji":"$emoji","emoji_url":${image?.let { "\"$it\"" } ?: "null"},"future":true}"""
            val mapped = json.decodeFromString<NotificationDto>(source).toDomain()
            val restored = json.decodeFromString<TimelineNotification>(json.encodeToString(mapped))
            assertEquals(emoji, restored.matchingReaction?.name)
            assertEquals(image, restored.matchingReaction?.imageUrl)
        }
    }

    @Test fun fedibirdNotificationReactionObjectIsPreferredEvenWithoutStatusActorIds() {
        val source = """{"id":"opaque","type":"emoji_reaction","created_at":"now","account":$author,
            "emoji_reaction":{"name":"blob","count":1,"me":false,"url":"https://example.org/blob.gif","static_url":"https://example.org/blob.png","domain":"remote.example"}}"""
        val mapped = json.decodeFromString<NotificationDto>(source).toDomain()
        assertEquals("blob", mapped.matchingReaction?.name)
        assertEquals("https://example.org/blob.gif", mapped.matchingReaction?.imageUrl)
    }

    @Test fun pleromaNestedAggregateSupportsActorFallbackWithoutEnablingFedibirdOperations() {
        val aggregate = """[{"name":"☕","count":1,"account_ids":["actor"]}]"""
        for (extra in listOf("", "\"emoji_reactions\":$aggregate,")) {
            val post = status("""$extra"pleroma":{"emoji_reactions":$aggregate}""")
            val mapped = json.decodeFromString<StatusDto>(post).toDomain()
            assertFalse(mapped.supportsEmojiReactions)
            assertEquals("☕", mapped.reactions.single().name)
            val source = """{"id":"opaque","type":"pleroma:emoji_reaction","created_at":"now","account":$author,"status":$post}"""
            assertEquals("☕", json.decodeFromString<NotificationDto>(source).toDomain().matchingReaction?.name)
        }
        assertTrue(json.decodeFromString<StatusDto>(status(""""emoji_reactions":[]""")).toDomain().supportsEmojiReactions)
    }

    @Test fun legacyCachedNotificationStillDecodesWithoutDirectEmojiFields() {
        val old = """{"id":"old","type":"emoji_reaction","createdAt":"now",
            "account":{"id":"actor","displayName":"Actor","accountName":"actor","avatarUrl":""},"status":null}"""
        assertNull(json.decodeFromString<TimelineNotification>(old).matchingReaction)
    }
}
