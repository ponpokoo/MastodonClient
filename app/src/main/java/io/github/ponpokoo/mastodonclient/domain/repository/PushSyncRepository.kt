package io.github.ponpokoo.mastodonclient.domain.repository

/** Recover active Push accounts; ordinary foreground checks are throttled. */
fun interface PushSyncRepository {
    suspend fun recover(sessionId: String?, force: Boolean)
}
