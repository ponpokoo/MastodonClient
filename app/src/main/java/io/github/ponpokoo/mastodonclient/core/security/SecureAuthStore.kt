package io.github.ponpokoo.mastodonclient.core.security

import android.content.Context
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable as runCatching
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
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
    val scopes: String = "read write",
    val reauthorizeSessionId: String? = null,
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
    val scopes: String = "",
    val avatarRevision: Long = 0,
)

interface AuthStore {
    suspend fun moveSession(sessionId: String, beforeSessionId: String?) { throw UnsupportedOperationException() }
    fun observeSessions(): kotlinx.coroutines.flow.Flow<List<AccountSession>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun updateAccountDisplay(expected: AccountSession, author: StatusAuthor): AccountSession? = null
    suspend fun findApplication(instanceUrl: String): RegisteredApplication?
    suspend fun saveApplication(application: RegisteredApplication)
    suspend fun savePending(pending: PendingOAuth)
    suspend fun getPending(): PendingOAuth?
    suspend fun clearPending()
    suspend fun saveSession(session: AccountSession)
    suspend fun getSessions(): List<AccountSession>
    suspend fun getSession(): AccountSession?
    suspend fun setActiveSession(sessionId: String): AccountSession?
    suspend fun removeSession(sessionId: String)
}

class SecureAuthStore internal constructor(
    private val dataStore: androidx.datastore.core.DataStore<Preferences>,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuthStore {
    constructor(context: Context, cipher: KeystoreCipher = KeystoreCipher(), json: Json = Json { ignoreUnknownKeys = true }) :
        this(context.applicationContext.authDataStore, cipher::encrypt, cipher::decrypt, json)

    override fun observeSessions() = dataStore.data.map { sessionsFrom(it) }.distinctUntilChanged()

    override suspend fun updateAccountDisplay(expected: AccountSession, author: StatusAuthor): AccountSession? {
        var updated: AccountSession? = null
        val caller = kotlinx.coroutines.currentCoroutineContext()
        dataStore.edit { preferences ->
            caller.ensureActive()
            val sessions = sessionsFrom(preferences)
            val current = sessions.firstOrNull { it.sessionId == expected.sessionId }
            if (current?.hasSameCredentials(expected) == true && author.id == current.accountId) {
                val replacement = current.copy(username = author.accountName, displayName = author.displayName,
                    avatarUrl = author.avatarUrl, avatarRevision = current.avatarRevision + 1)
                putSessions(preferences, sessions.map { if (it.sessionId == current.sessionId) replacement else it })
                caller.ensureActive()
                updated = replacement
            }
        }
        return updated
    }

    override suspend fun findApplication(instanceUrl: String): RegisteredApplication? =
        read<List<RegisteredApplication>>(REGISTRATIONS)
            ?.firstOrNull { it.instanceUrl == instanceUrl }

    override suspend fun saveApplication(application: RegisteredApplication) {
        val applications = read<List<RegisteredApplication>>(REGISTRATIONS).orEmpty()
            .filterNot { it.instanceUrl == application.instanceUrl } + application
        write(REGISTRATIONS, json.encodeToString(applications))
    }

    override suspend fun savePending(pending: PendingOAuth) =
        write(PENDING, json.encodeToString(pending))

    override suspend fun getPending(): PendingOAuth? = read(PENDING)

    override suspend fun clearPending() {
        dataStore.edit { it.remove(PENDING) }
    }

    override suspend fun saveSession(session: AccountSession) {
        dataStore.edit { preferences ->
            val sessions = sessionsFrom(preferences).toMutableList()
            val index = sessions.indexOfFirst {
                it.instanceUrl == session.instanceUrl && it.accountId == session.accountId
            }
            if (index >= 0) sessions[index] = session else sessions.add(session)
            putSessions(preferences, sessions)
            preferences[ACTIVE_SESSION_ID] = session.sessionId
        }
    }

    override suspend fun getSessions(): List<AccountSession> {
        return sessionsFrom(dataStore.data.first())
    }

    override suspend fun getSession(): AccountSession? {
        val preferences = dataStore.data.first()
        val sessions = sessionsFrom(preferences)
        val activeId = preferences[ACTIVE_SESSION_ID]
        return sessions.firstOrNull { it.sessionId == activeId } ?: sessions.firstOrNull()
    }

    override suspend fun setActiveSession(sessionId: String): AccountSession? {
        var session: AccountSession? = null
        dataStore.edit { preferences ->
            session = sessionsFrom(preferences).firstOrNull { it.sessionId == sessionId }
            if (session != null) preferences[ACTIVE_SESSION_ID] = sessionId
        }
        return session
    }

    override suspend fun removeSession(sessionId: String) {
        dataStore.edit { preferences ->
            val remaining = sessionsFrom(preferences).filterNot { it.sessionId == sessionId }
            if (remaining.isEmpty()) {
                preferences.remove(SESSIONS)
                preferences.remove(ACTIVE_SESSION_ID)
                preferences.remove(SESSION)
            } else {
                putSessions(preferences, remaining)
                if (preferences[ACTIVE_SESSION_ID] == sessionId) preferences[ACTIVE_SESSION_ID] = remaining.first().sessionId
            }
        }
    }

    override suspend fun moveSession(sessionId: String, beforeSessionId: String?) {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        dataStore.edit { preferences ->
            caller.ensureActive()
            val sessions = sessionsFrom(preferences).toMutableList()
            val index = sessions.indexOfFirst { it.sessionId == sessionId }
            check(index >= 0) { "アカウントの登録が変更されました" }
            if (beforeSessionId == sessionId) return@edit
            check(beforeSessionId == null || sessions.any { it.sessionId == beforeSessionId }) { "移動先の登録が変更されました" }
            val active = sessions.firstOrNull { it.sessionId == preferences[ACTIVE_SESSION_ID] } ?: sessions.first()
            val moving = sessions.removeAt(index)
            val destination = if (beforeSessionId == null) sessions.size else sessions.indexOfFirst { it.sessionId == beforeSessionId }
            sessions.add(destination, moving)
            if (destination != index) {
                preferences[ACTIVE_SESSION_ID] = active.sessionId
                putSessions(preferences, sessions)
                caller.ensureActive()
            }
        }
    }

    private fun sessionsFrom(preferences: Preferences): List<AccountSession> {
        fun <T> decode(key: Preferences.Key<String>, decode: (String) -> T): T? =
            preferences[key]?.let { encrypted -> runCatching { decode(decrypt(encrypted)) }.getOrNull() }
        val sessions = decode(SESSIONS) { json.decodeFromString<List<StoredSession>>(it) }
        if (!sessions.isNullOrEmpty()) return sessions.map(StoredSession::toDomain)
        return listOfNotNull(decode(SESSION) { json.decodeFromString<StoredSession>(it) }?.toDomain())
    }

    private fun putSessions(preferences: androidx.datastore.preferences.core.MutablePreferences, sessions: List<AccountSession>) {
        preferences[SESSIONS] = encrypt(json.encodeToString(sessions.map(AccountSession::toStored)))
        // Preserve selection when migrating a legacy record as part of a metadata write.
        if (preferences[ACTIVE_SESSION_ID] == null) sessions.firstOrNull()?.let { preferences[ACTIVE_SESSION_ID] = it.sessionId }
        preferences.remove(SESSION)
    }

    private suspend inline fun <reified T> read(key: Preferences.Key<String>): T? = runCatching {
        val encrypted = dataStore.data.map { it[key] }.first() ?: return null
        json.decodeFromString<T>(decrypt(encrypted))
    }.getOrNull()

    private suspend fun write(key: Preferences.Key<String>, plaintext: String) {
        val encrypted = encrypt(plaintext)
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
    scopes = scopes,
    avatarRevision = avatarRevision,
)

private fun StoredSession.toDomain() = AccountSession(
    sessionId = sessionId,
    instanceUrl = instanceUrl,
    accountId = accountId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    accessToken = accessToken,
    scopes = scopes,
    avatarRevision = avatarRevision,
)
