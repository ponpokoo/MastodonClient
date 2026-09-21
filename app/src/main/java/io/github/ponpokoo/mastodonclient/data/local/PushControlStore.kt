package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.KeystoreCipher
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PushIntent(val enabled: Boolean = false, val cleanup: AccountSession? = null, val awaitingAuthorization: Boolean = false) {
    override fun toString() = "PushIntent(enabled=$enabled, cleanup=${cleanup != null})"
}
@Serializable
class PushControlData(val intents: Map<String, PushIntent> = emptyMap(), val token: String? = null)
interface PushControlStore {
    suspend fun read(): PushControlData
    suspend fun write(value: PushControlData)
}
private val Context.controlStore by preferencesDataStore("secure_push_control")
class EncryptedPushControlStore(context: Context) : PushControlStore {
    private val data = context.applicationContext.controlStore
    private val cipher = KeystoreCipher()
    override suspend fun read(): PushControlData = data.data.first()[key]?.let { json.decodeFromString(cipher.decrypt(it)) } ?: PushControlData()
    override suspend fun write(value: PushControlData) {
        val encrypted = cipher.encrypt(json.encodeToString(value))
        data.edit { it[key] = encrypted }
    }
    private companion object {
        val key = stringPreferencesKey("control")
        val json = Json { ignoreUnknownKeys = true }
    }
}
