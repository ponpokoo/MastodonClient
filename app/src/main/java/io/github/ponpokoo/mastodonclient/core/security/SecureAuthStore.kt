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
    val scopes: String = "read",
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
        val sessions = getSessions().filterNot {
            it.instanceUrl == session.instanceUrl && it.accountId == session.accountId
        } + session
        write(SESSIONS, json.encodeToString(sessions.map(AccountSession::toStored)))
        dataStore.edit { it[ACTIVE_SESSION_ID] = session.sessionId }
        dataStore.edit { it.remove(SESSION) }
    }

    suspend fun getSessions(): List<AccountSession> {
        val stored = read<List<StoredSession>>(SESSIONS)?.map(StoredSession::toDomain).orEmpty()
        if (stored.isNotEmpty()) return stored
        val legacy = read<StoredSession>(SESSION)?.toDomain() ?: return emptyList()
        write(SESSIONS, json.encodeToString(listOf(legacy.toStored())))
        dataStore.edit { it[ACTIVE_SESSION_ID] = legacy.sessionId; it.remove(SESSION) }
        return listOf(legacy)
    }

    suspend fun getSession(): AccountSession? {
        val sessions = getSessions()
        val activeId = dataStore.data.map { it[ACTIVE_SESSION_ID] }.first()
        return sessions.firstOrNull { it.sessionId == activeId } ?: sessions.firstOrNull()
    }

    suspend fun setActiveSession(sessionId: String): AccountSession? {
        val session = getSessions().firstOrNull { it.sessionId == sessionId } ?: return null
        dataStore.edit { it[ACTIVE_SESSION_ID] = sessionId }
        return session
    }

    suspend fun clearSession() {
        val current = getSession() ?: return
        val remaining = getSessions().filterNot { it.sessionId == current.sessionId }
        if (remaining.isEmpty()) {
            dataStore.edit {
                it.remove(SESSIONS)
                it.remove(ACTIVE_SESSION_ID)
                it.remove(SESSION)
            }
        } else {
            write(SESSIONS, json.encodeToString(remaining.map(AccountSession::toStored)))
            dataStore.edit { it[ACTIVE_SESSION_ID] = remaining.first().sessionId }
        }
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
        val SESSIONS = stringPreferencesKey("account_sessions")
        val ACTIVE_SESSION_ID = stringPreferencesKey("active_account_session_id")
    }
}

private fun AccountSession.toStored() = StoredSession(
    sessionId = sessionId,
    instanceUrl = instanceUrl,
    accountId = accountId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    accessToken = accessToken,
)

private fun StoredSession.toDomain() = AccountSession(
    sessionId = sessionId,
    instanceUrl = instanceUrl,
    accountId = accountId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    accessToken = accessToken,
)
