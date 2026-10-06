package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable

enum class NotificationCategory(val label: String) {
    All("すべて"), Mentions("メンション"), Reactions("リアクション");

    fun includes(notification: TimelineNotification): Boolean = when (this) {
        All -> true
        Mentions -> notification.type.equals("mention", true) || notification.type.equals("reply", true)
        Reactions -> notification.type.contains("reaction", true)
    }
}

data class NotificationCapabilities(
    val supportsTypeFiltering: Boolean = false,
    val supportsEmojiReactions: Boolean = false,
)

/** IDs remain opaque. A missing boundary is not evidence that an entire page is unread. */
@Serializable
data class NotificationReadState(
    val viewedIds: List<String> = emptyList(),
    val latestViewedAllId: String? = null,
)
