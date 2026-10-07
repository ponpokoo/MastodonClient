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
    val avatarRevision: Long = 0,
)

/** Display metadata does not change the authentication context. */
fun AccountSession.hasSameCredentials(other: AccountSession?): Boolean =
    other != null && sessionId == other.sessionId && instanceUrl == other.instanceUrl &&
        accountId == other.accountId && accessToken == other.accessToken && scopes == other.scopes

fun StatusAuthor.withAccountDisplay(session: AccountSession?): StatusAuthor =
    if (session == null || id != session.accountId) this else copy(
        displayName = session.displayName.ifBlank { session.username }, accountName = session.username,
        avatarUrl = session.avatarUrl, avatarRevision = session.avatarRevision,
    )
