package io.github.ponpokoo.mastodonclient.core.image

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

internal data class AvatarValidation(val validatedAt: Long = 0, val retryAfter: Long = 0)

internal interface AvatarValidationStore {
    suspend fun read(url: String): AvatarValidation
    suspend fun write(url: String, validation: AvatarValidation)
    suspend fun clear()
}

/** Metadata only, in the existing app preferences. URLs and image bytes are not stored here. */
internal class PreferencesAvatarValidationStore(private val dataStore: DataStore<Preferences>) : AvatarValidationStore {
    override suspend fun read(url: String): AvatarValidation =
        decode(dataStore.data.first()[key(url)])

    override suspend fun write(url: String, validation: AvatarValidation) {
        dataStore.edit { stored ->
            stored[key(url)] = "${validation.validatedAt},${validation.retryAfter}"
            // Reclaim irrelevant expired metadata on writes, without discarding any fresh URLs
            // or enumerating Coil images. A fixed entry cap could cause downloads within 24 hours.
            val time = if (validation.retryAfter > 0) validation.retryAfter - AvatarCacheCoordinator.RETRY_INTERVAL
                else validation.validatedAt
            stored.asMap().filterKeys { it.name.startsWith(PREFIX) }.forEach { (key, value) ->
                val entry = decode(value as? String)
                if (entry.validatedAt < time - AvatarCacheCoordinator.VALIDATION_INTERVAL && entry.retryAfter <= time) {
                    stored.remove(stringPreferencesKey(key.name))
                }
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { stored ->
            stored.asMap().keys.filter { it.name.startsWith(PREFIX) }
                .forEach { stored.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun key(url: String) = stringPreferencesKey(PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })

    private fun decode(value: String?): AvatarValidation {
        val parts = value?.split(',') ?: return AvatarValidation()
        return AvatarValidation(parts.getOrNull(0)?.toLongOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0)
    }

    private companion object {
        const val PREFIX = "avatar_validation:"
    }
}
