package io.github.ponpokoo.mastodonclient.data.repository

import androidx.core.net.toUri
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.PendingOAuth
import io.github.ponpokoo.mastodonclient.core.security.PkceGenerator
import io.github.ponpokoo.mastodonclient.core.security.RegisteredApplication
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import java.util.UUID

class DefaultAuthRepository(
    private val apiClientFactory: ApiClientFactory,
    private val authStore: SecureAuthStore,
) : AuthRepository {
    override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> = runCatching {
        val api = apiClientFactory.create(instanceUrl)
        val storedApplication = authStore.findApplication(instanceUrl)
        val application = storedApplication?.takeIf { it.scopes == SCOPES } ?: api.createApplication(
            clientName = CLIENT_NAME,
            redirectUris = REDIRECT_URI,
            scopes = SCOPES,
            website = WEBSITE,
        ).let {
            RegisteredApplication(instanceUrl, it.clientId, it.clientSecret, SCOPES)
        }.also { authStore.saveApplication(it) }

        val pkce = PkceGenerator.generate()
        val state = PkceGenerator.randomState()
        authStore.savePending(
            PendingOAuth(
                instanceUrl = instanceUrl,
                clientId = application.clientId,
                clientSecret = application.clientSecret,
                codeVerifier = pkce.verifier,
                state = state,
            ),
        )

        instanceUrl.toUri().buildUpon()
            .appendEncodedPath("oauth/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", application.clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> = runCatching {
        val callback = callbackUrl.toUri()
        require(callback.scheme == REDIRECT_SCHEME && callback.host == "oauth" && callback.path == "/callback") {
            "OAuthコールバックが正しくありません"
        }
        callback.getQueryParameter("error")?.let { error ->
            throw IllegalStateException(callback.getQueryParameter("error_description") ?: error)
        }

        val pending = authStore.getPending() ?: error("認証情報が失効しました。もう一度ログインしてください")
        require(callback.getQueryParameter("state") == pending.state) { "OAuth stateが一致しません" }
        val code = callback.getQueryParameter("code") ?: error("認証コードがありません")

        val token = apiClientFactory.create(pending.instanceUrl).exchangeToken(
            code = code,
            clientId = pending.clientId,
            clientSecret = pending.clientSecret,
            redirectUri = REDIRECT_URI,
            codeVerifier = pending.codeVerifier,
        )
        require(token.tokenType.equals("Bearer", ignoreCase = true)) { "未対応のトークン形式です" }

        val account = apiClientFactory.create(pending.instanceUrl, token.accessToken).verifyCredentials()
        val session = AccountSession(
            sessionId = UUID.randomUUID().toString(),
            instanceUrl = pending.instanceUrl,
            accountId = account.id,
            username = account.acct,
            displayName = account.displayName,
            avatarUrl = account.avatar,
            accessToken = token.accessToken,
        )
        authStore.saveSession(session)
        authStore.clearPending()
        session
    }

    override suspend fun restoreSession(): AccountSession? = authStore.getSession()

    override suspend fun logout() = authStore.clearSession()

    companion object {
        const val REDIRECT_SCHEME = "io.github.ponpokoo.mastodonclient"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth/callback"
        const val SCOPES = "read write:statuses write:favourites"
        private const val CLIENT_NAME = "Mastodon Client for Android"
        private const val WEBSITE = "https://github.com/ponpokoo/MastodonClient"
    }
}
