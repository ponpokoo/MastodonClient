package io.github.ponpokoo.mastodonclient.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Login : Route

    @Serializable
    data object Timeline : Route

    @Serializable
    data class StatusDetail(val statusId: String) : Route

    @Serializable
    data class ComposePost(val replyToId: String? = null) : Route

    @Serializable
    data class WebPage(val url: String) : Route

    @Serializable
    data class AccountProfile(val accountId: String) : Route

    @Serializable
    data class HashtagTimeline(val hashtag: String) : Route
}
