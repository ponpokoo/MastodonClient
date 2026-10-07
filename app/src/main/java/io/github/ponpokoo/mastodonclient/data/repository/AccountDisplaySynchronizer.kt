package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.core.security.AuthStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by authentication and profile reads: latest request wins within one selection epoch. */
class AccountDisplaySynchronizer(private val store: AuthStore) {
    class Request internal constructor(val session: AccountSession, internal val epoch: Long, internal val sequence: Long)
    private val mutex = Mutex()
    private var epoch = 0L
    private var sequence = 0L
    private val latest = mutableMapOf<String, Long>()

    suspend fun invalidate() = mutex.withLock {
        epoch++
        latest.clear()
    }

    /** Do not admit a metadata request between epoch invalidation and the authentication write. */
    suspend fun <T> changeRegistration(block: suspend () -> T): T = mutex.withLock {
        epoch++
        latest.clear()
        block()
    }

    suspend fun begin(session: AccountSession): Request = mutex.withLock {
        Request(session, epoch, ++sequence).also { latest[session.sessionId] = it.sequence }
    }

    suspend fun commit(request: Request?, author: StatusAuthor): AccountSession? {
        if (request == null || author.id != request.session.accountId) return null
        currentCoroutineContext().ensureActive()
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (request.epoch != epoch) return null
            // A local save failure must not turn a successful profile read/edit into a UI error.
            // The store checks registration and credentials again inside its atomic write.
            runCatchingCancellable {
                if (latest[request.session.sessionId] == request.sequence) store.updateAccountDisplay(request.session, author)
                else store.getSessions().firstOrNull { it.hasSameCredentials(request.session) }
            }.getOrNull()
        }
    }
}
