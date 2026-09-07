package io.github.ponpokoo.mastodonclient.domain.model

data class AccountSession(
    val sessionId: String,
    val instanceUrl: String,
    val accountId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val accessToken: String,
)
