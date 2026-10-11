package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials

interface AuthRepository {
    suspend fun retryAccountCleanup() = Unit
    suspend fun moveAccount(sessionId: String, beforeSessionId: String?): Result<Unit> = Result.failure(UnsupportedOperationException())
    /** Remove only the active credentials that the user confirmed. False means the target changed. */
    suspend fun logout(expected: AccountSession): Boolean {
        if (!expected.hasSameCredentials(restoreSession())) return false
        logout()
        return true
    }
    fun observeSessions(): kotlinx.coroutines.flow.Flow<List<AccountSession>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun refreshAccountDisplay(session: AccountSession): Result<Unit> = Result.success(Unit)
    suspend fun createAuthorizationUrl(instanceUrl: String): Result<String>
    suspend fun createPushAuthorizationUrl(sessionId: String): Result<String> = Result.failure(UnsupportedOperationException())
    suspend fun pendingPushAuthorization(): Boolean = false
    suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession>
    suspend fun restoreSession(): AccountSession?
    suspend fun getSessions(): List<AccountSession> = listOfNotNull(restoreSession())
    suspend fun switchSession(sessionId: String): AccountSession? =
        getSessions().firstOrNull { it.sessionId == sessionId }
    suspend fun logout()
}
