package io.github.ponpokoo.mastodonclient.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Login : Route

    @Serializable
    data object AddAccount : Route

    @Serializable
    data object Timeline : Route

    @Serializable
    data class StatusDetail(val statusId: String) : Route

    @Serializable
    data class ComposePost(
        val replyToId: String? = null,
        val editStatusId: String? = null,
    ) : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class WebPage(val url: String) : Route

    @Serializable
    data class AccountProfile(val accountId: String, val openEditor: Boolean = false) : Route

    @Serializable
    data class AccountList(
        val accountId: String,
        val followers: Boolean,
    ) : Route

    @Serializable
    data class HashtagTimeline(val hashtag: String) : Route

    @Serializable
    data object Lists : Route

    @Serializable
    data class SavedTimeline(
        val kind: String,
        val listId: String? = null,
        val title: String,
    ) : Route

    @Serializable
    data class MediaViewer(
        val url: String,
        val type: String,
        val description: String? = null,
        val previewUrl: String? = null,
    ) : Route
}
