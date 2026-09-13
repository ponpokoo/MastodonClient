package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable

data class TimelineStatus(
    val timelineId: String,
    val statusId: String,
    val createdAt: String,
    val author: StatusAuthor,
    val boostedBy: StatusAuthor?,
    val contentHtml: String,
    val spoilerText: String,
    val sensitive: Boolean,
    val visibility: String,
    val url: String?,
    val repliesCount: Long,
    val boostsCount: Long,
    val favouritesCount: Long,
    val favourited: Boolean = false,
    val reblogged: Boolean = false,
    val bookmarked: Boolean = false,
    val pinned: Boolean = false,
    val applicationName: String? = null,
    val reactions: List<EmojiReaction> = emptyList(),
    val supportsEmojiReactions: Boolean = false,
    val previewCard: PreviewCard? = null,
    val mediaAttachments: List<MediaAttachment>,
    val customEmojis: Map<String, String> = emptyMap(),
    val mentions: List<StatusMention> = emptyList(),
    val quoteApproval: String? = null,
)

data class StatusMention(val accountId: String, val accountName: String, val url: String)

fun TimelineStatus.mentionedAccountIdFor(link: String): String? {
    if (link.isBlank()) return null
    val normalized = link.substringBefore('#').substringBefore('?').trimEnd('/')
    return mentions.firstOrNull { mention ->
        mention.url.isNotBlank() && mention.url.substringBefore('#').substringBefore('?').trimEnd('/') == normalized
    }?.accountId
}

data class PreviewCard(
    val url: String,
    val title: String,
    val description: String,
    val type: String,
    val byline: String,
    val imageUrl: String?,
    val aspectRatio: Float?,
)

data class EmojiReaction(
    val name: String,
    val count: Long,
    val reactedByMe: Boolean,
    val imageUrl: String?,
    val accountIds: Set<String>,
)

data class StatusAuthor(
    val id: String,
    val displayName: String,
    val accountName: String,
    val avatarUrl: String,
    val customEmojis: Map<String, String> = emptyMap(),
)

@Serializable
data class MediaAttachment(
    val id: String,
    val type: String,
    val url: String?,
    val previewUrl: String?,
    val description: String?,
)

data class TimelinePage(
    val statuses: List<TimelineStatus>,
    val nextMaxId: String?,
    val endReached: Boolean,
)

data class StatusDetail(
    val status: TimelineStatus,
    val ancestors: List<TimelineStatus>,
    val descendants: List<TimelineStatus>,
)

data class EditableStatus(
    val id: String,
    val text: String,
    val spoilerText: String,
    val sensitive: Boolean,
    val language: String? = null,
)

data class ServerAnnouncement(
    val id: String,
    val contentHtml: String,
    val publishedAt: String?,
    val updatedAt: String?,
    val read: Boolean,
)
