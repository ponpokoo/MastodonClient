package io.github.ponpokoo.mastodonclient.domain.model

import kotlinx.serialization.Serializable

enum class NotificationCategory(val label: String) {
    All("すべて"), Mentions("メンション"), Reactions("リアクション");

    fun includes(notification: TimelineNotification): Boolean = when (this) {
        All -> true
        Mentions -> notification.type.equals("mention", true) || notification.type.equals("reply", true)
        Reactions -> notification.isEmojiReaction
    }
}

@Serializable
data class NotificationCapabilities(
    val supportsTypeFiltering: Boolean = false,
    val supportsEmojiReactions: Boolean = false,
    val reactionApi: EmojiReactionApi = if (supportsEmojiReactions) EmojiReactionApi.Fedibird else EmojiReactionApi.Unknown,
    val supportsCustomEmojiReactions: Boolean = false,
    val usesIncludeTypes: Boolean = false,
    val checkedAtEpochMillis: Long = 0,
    val pushAlerts: Set<String>? = null,
    // A failed refresh does not change the durable confirmation time or capabilities.
    @kotlinx.serialization.Transient val retryAtEpochMillis: Long = 0,
) {
    // Only the Fedibird contract currently has implemented write/account-list APIs.
    val supportsReactionActions: Boolean get() = reactionApi == EmojiReactionApi.Fedibird
    val supportsReactionAccounts: Boolean get() = reactionApi == EmojiReactionApi.Fedibird
    // The sent-reaction history endpoint is part of the Fedibird/kmyblue contract.
    val supportsReactionHistory: Boolean get() = reactionApi == EmojiReactionApi.Fedibird

    fun isRetryPending(now: Long): Boolean =
        retryAtEpochMillis > 0 && now in (retryAtEpochMillis - RETRY_MILLIS) until retryAtEpochMillis

    val reactionNotificationTypes: List<String>
        get() = when (reactionApi) {
            EmojiReactionApi.Fedibird -> listOf("emoji_reaction")
            EmojiReactionApi.Pleroma -> listOf("pleroma:emoji_reaction")
            else -> emptyList()
        }
    // Kept separate from notification filtering and status DTO field availability.
    val additionalPushAlerts: Set<String> get() = pushAlerts ?: reactionNotificationTypes.toSet()

    companion object {
        const val TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val RETRY_MILLIS = 5 * 60 * 1_000L
    }
}

@Serializable
enum class EmojiReactionApi { Unknown, Unsupported, Fedibird, Pleroma }

/** Shared server metadata; retain the existing serialized model and preference key. */
typealias ServerCapabilities = NotificationCapabilities

/** IDs remain opaque. A missing boundary is not evidence that an entire page is unread. */
@Serializable
data class NotificationReadState(
    val viewedIds: List<String> = emptyList(),
    val latestViewedAllId: String? = null,
)
