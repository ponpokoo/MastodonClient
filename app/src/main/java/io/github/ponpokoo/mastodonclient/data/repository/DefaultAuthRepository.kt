package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable as runCatching
import androidx.core.net.toUri
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.PendingOAuth
import io.github.ponpokoo.mastodonclient.core.security.PkceGenerator
import io.github.ponpokoo.mastodonclient.core.security.RegisteredApplication
import io.github.ponpokoo.mastodonclient.core.security.AuthStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import java.util.UUID

class DefaultAuthRepository(
    private val apiClientFactory: ApiClientFactory,
    private val authStore: AuthStore,
    private val pushLifecycle: io.github.ponpokoo.mastodonclient.domain.repository.PushAuthLifecycle? = null,
) : AuthRepository {
    override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> = authorizationUrl(instanceUrl, SCOPES)

    override suspend fun pendingPushAuthorization() = authStore.getPending()?.reauthorizeSessionId != null

    override suspend fun createPushAuthorizationUrl(sessionId: String): Result<String> = runCatching {
        val session = authStore.getSessions().firstOrNull { it.sessionId == sessionId } ?: error("アカウントが見つかりません")
        pushLifecycle?.beforeReauthorization(session)
        authorizationUrl(session.instanceUrl, "$SCOPES push", sessionId).getOrThrow()
    }

    private suspend fun authorizationUrl(instanceUrl: String, scopes: String, reauthorizeSessionId: String? = null): Result<String> = runCatching {
        val api = apiClientFactory.create(instanceUrl)
        val storedApplication = authStore.findApplication(instanceUrl)
        val application = storedApplication?.takeIf { it.scopes == scopes } ?: api.createApplication(
            clientName = CLIENT_NAME,
            redirectUris = REDIRECT_URI,
            scopes = scopes,
            website = WEBSITE,
        ).let {
            RegisteredApplication(instanceUrl, it.clientId, it.clientSecret, scopes)
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
                scopes = scopes,
                reauthorizeSessionId = reauthorizeSessionId,
            ),
        )

        instanceUrl.toUri().buildUpon()
            .appendEncodedPath("oauth/authorize")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", application.clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", scopes)
            .apply { if (reauthorizeSessionId != null) appendQueryParameter("force_login", "true") }
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
        val pending = authStore.getPending() ?: error("認証情報が失効しました。もう一度ログインしてください")
        require(callback.getQueryParameter("state") == pending.state) { "OAuth stateが一致しません" }
        callback.getQueryParameter("error")?.let {
            authStore.clearPending()
            error("認証がキャンセルされました。通知設定から再試行できます。")
        }
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
        val previous = authStore.getSessions().firstOrNull { it.instanceUrl == pending.instanceUrl && it.accountId == account.id }
        if (pending.reauthorizeSessionId != null) {
            require(previous?.sessionId == pending.reauthorizeSessionId) { "元のアカウントと一致しません。正しいアカウントで再認証してください。" }
            require("push" in (token.scope ?: pending.scopes).split(' ')) { "通知の権限が許可されていません" }
        }
        // Cleanup before replacing any previously logged-in account, including the Add Account route.
        if (previous != null && pending.reauthorizeSessionId == null) pushLifecycle?.beforeLogout(previous)
        val session = AccountSession(
            sessionId = if (pending.reauthorizeSessionId != null) pending.reauthorizeSessionId else UUID.randomUUID().toString(),
            instanceUrl = pending.instanceUrl,
            accountId = account.id,
            username = account.acct,
            displayName = account.displayName,
            avatarUrl = account.avatar,
            accessToken = token.accessToken,
            scopes = token.scope ?: pending.scopes,
        )
        authStore.saveSession(session)
        authStore.clearPending()
        // Authentication is committed even if notification registration needs a later retry.
        runCatching { pushLifecycle?.afterAuthorization(session) }
        session
    }

    override suspend fun restoreSession(): AccountSession? = authStore.getSession()

    override suspend fun getSessions(): List<AccountSession> = authStore.getSessions()

    override suspend fun switchSession(sessionId: String): AccountSession? =
        authStore.setActiveSession(sessionId)

    override suspend fun logout() {
        val session = authStore.getSession() ?: return
        pushLifecycle?.beforeLogout(session)
        authStore.removeSession(session.sessionId)
        if (authStore.getPending()?.reauthorizeSessionId == session.sessionId) authStore.clearPending()
    }

    companion object {
        const val REDIRECT_SCHEME = "io.github.ponpokoo.mastodonclient"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth/callback"
        const val SCOPES = "read write"
        private const val CLIENT_NAME = "Nagisa for Mastodon"
        private const val WEBSITE = "https://github.com/ponpokoo/MastodonClient"
    }
}
