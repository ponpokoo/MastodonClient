package io.github.ponpokoo.mastodonclient.domain.model

@kotlinx.serialization.Serializable
data class AccountSession(
    val sessionId: String,
    val instanceUrl: String,
    val accountId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val accessToken: String,
    val scopes: String = "",
)
