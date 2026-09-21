package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

interface AuthRepository {
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
