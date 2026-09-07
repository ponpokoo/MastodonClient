package io.github.ponpokoo.mastodonclient.core.security

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.authDataStore by preferencesDataStore(name = "secure_auth")

@Serializable
data class RegisteredApplication(
    val instanceUrl: String,
    val clientId: String,
    val clientSecret: String,
)

@Serializable
data class PendingOAuth(
    val instanceUrl: String,
    val clientId: String,
    val clientSecret: String,
    val codeVerifier: String,
    val state: String,
)

@Serializable
private data class StoredSession(
    val sessionId: String,
    val instanceUrl: String,
    val accountId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val accessToken: String,
)

class SecureAuthStore(
    context: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val dataStore = context.applicationContext.authDataStore

    suspend fun findApplication(instanceUrl: String): RegisteredApplication? =
        read<List<RegisteredApplication>>(REGISTRATIONS)
            ?.firstOrNull { it.instanceUrl == instanceUrl }

    suspend fun saveApplication(application: RegisteredApplication) {
        val applications = read<List<RegisteredApplication>>(REGISTRATIONS).orEmpty()
            .filterNot { it.instanceUrl == application.instanceUrl } + application
        write(REGISTRATIONS, json.encodeToString(applications))
    }

    suspend fun savePending(pending: PendingOAuth) =
        write(PENDING, json.encodeToString(pending))

    suspend fun getPending(): PendingOAuth? = read(PENDING)

    suspend fun clearPending() {
        dataStore.edit { it.remove(PENDING) }
    }

    suspend fun saveSession(session: AccountSession) {
        write(
            SESSION,
            json.encodeToString(
                StoredSession(
                    sessionId = session.sessionId,
                    instanceUrl = session.instanceUrl,
                    accountId = session.accountId,
                    username = session.username,
                    displayName = session.displayName,
                    avatarUrl = session.avatarUrl,
                    accessToken = session.accessToken,
                ),
            ),
        )
    }

    suspend fun getSession(): AccountSession? = read<StoredSession>(SESSION)?.let {
        AccountSession(
            sessionId = it.sessionId,
            instanceUrl = it.instanceUrl,
            accountId = it.accountId,
            username = it.username,
            displayName = it.displayName,
            avatarUrl = it.avatarUrl,
            accessToken = it.accessToken,
        )
    }

    suspend fun clearSession() {
        dataStore.edit { it.remove(SESSION) }
    }

    private suspend inline fun <reified T> read(key: Preferences.Key<String>): T? = runCatching {
        val encrypted = dataStore.data.map { it[key] }.first() ?: return null
        json.decodeFromString<T>(cipher.decrypt(encrypted))
    }.getOrNull()

    private suspend fun write(key: Preferences.Key<String>, plaintext: String) {
        val encrypted = cipher.encrypt(plaintext)
        dataStore.edit { it[key] = encrypted }
    }

    private companion object {
        val REGISTRATIONS = stringPreferencesKey("registrations")
        val PENDING = stringPreferencesKey("pending_oauth")
        val SESSION = stringPreferencesKey("account_session")
    }
}
