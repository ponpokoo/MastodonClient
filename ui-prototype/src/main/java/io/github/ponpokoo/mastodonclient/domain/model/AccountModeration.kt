package io.github.ponpokoo.mastodonclient.domain.model

/** Confirmed changes in this process, isolated by login session and instance. */
data class AccountModerationKey(val sessionId: String, val instanceUrl: String, val accountId: String)

data class AccountModerationState(
    val relationships: Map<AccountModerationKey, AccountRelationship> = emptyMap(),
    val cleanupFailures: Set<AccountModerationKey> = emptySet(),
    val wordMutes: Map<String, List<String>> = emptyMap(),
) {
    fun relationship(session: AccountSession, accountId: String) = relationships[key(session, accountId)]
    fun changed(session: AccountSession, accountId: String, relationship: AccountRelationship) =
        copy(relationships = relationships + (key(session, accountId) to relationship),
            cleanupFailures = cleanupFailures - key(session, accountId))

    fun cleanupFailed(session: AccountSession, accountId: String) =
        copy(cleanupFailures = cleanupFailures + key(session, accountId))
    fun hasCleanupFailure(session: AccountSession, accountId: String) = key(session, accountId) in cleanupFailures

    fun hides(session: AccountSession, status: TimelineStatus): Boolean =
        hidden(session, status.author.id) || status.boostedBy?.let { hidden(session, it.id) } == true

    fun hides(session: AccountSession, notification: TimelineNotification): Boolean =
        hiddenNotification(session, notification.account.id) || notification.status?.let { status ->
            hiddenNotification(session, status.author.id) || status.boostedBy?.let { hiddenNotification(session, it.id) } == true
        } == true

    private fun hiddenNotification(session: AccountSession, accountId: String) =
        relationship(session, accountId)?.let { it.blocking || (it.muting && it.mutingNotifications != false) } == true

    private fun hidden(session: AccountSession, accountId: String) =
        relationship(session, accountId)?.let { it.muting || it.blocking } == true

    private fun key(session: AccountSession, accountId: String) =
        AccountModerationKey(session.sessionId, session.instanceUrl.trimEnd('/'), accountId)
}
