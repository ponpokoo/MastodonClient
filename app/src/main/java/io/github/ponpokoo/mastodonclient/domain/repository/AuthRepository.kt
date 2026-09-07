package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession

interface AuthRepository {
    suspend fun createAuthorizationUrl(instanceUrl: String): Result<String>
    suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession>
    suspend fun restoreSession(): AccountSession?
    suspend fun logout()
}
