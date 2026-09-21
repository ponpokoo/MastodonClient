package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ponpokoo.mastodonclient.core.security.KeystoreCipher
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeys
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredPushRegistration(
    val credentialBinding: String,
    val relayIdentity: String,
    val registrationId: String,
    val managementToken: String,
    val keys: WebPushKeys,
    val endpoint: String? = null,
    val state: PushRegistrationState = PushRegistrationState.REGISTERING,
) {
    override fun toString() = "StoredPushRegistration(state=$state)"
}

interface PushRegistrationStore {
    suspend fun read(sessionId: String): StoredPushRegistration?
    suspend fun write(sessionId: String, record: StoredPushRegistration)
    suspend fun remove(sessionId: String)
}

private val Context.pushDataStore by preferencesDataStore(name = "secure_push")

class EncryptedPushRegistrationStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) : PushRegistrationStore {
    constructor(context: Context, cipher: KeystoreCipher = KeystoreCipher()) :
        this(context.applicationContext.pushDataStore, cipher::encrypt, cipher::decrypt)

    override suspend fun read(sessionId: String): StoredPushRegistration? {
        val encrypted = dataStore.data.first()[key(sessionId)] ?: return null
        // Corruption and invalidated keys must not be mistaken for a new installation.
        return json.decodeFromString(decrypt(encrypted))
    }

    override suspend fun write(sessionId: String, record: StoredPushRegistration) {
        val encrypted = encrypt(json.encodeToString(record))
        dataStore.edit { it[key(sessionId)] = encrypted }
    }

    override suspend fun remove(sessionId: String) { dataStore.edit { it.remove(key(sessionId)) } }
    private fun key(sessionId: String) = stringPreferencesKey("registration_$sessionId")
    private companion object { val json = Json { ignoreUnknownKeys = true } }
}
