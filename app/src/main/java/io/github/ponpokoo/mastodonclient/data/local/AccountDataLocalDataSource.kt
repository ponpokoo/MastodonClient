package io.github.ponpokoo.mastodonclient.data.local

import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import kotlinx.coroutines.CancellationException

fun interface AccountDataLocalDataSource {
    suspend fun deleteAccount(sessionId: String)
}

class DefaultAccountDataLocalDataSource(
    private val preferences: UserPreferencesStore,
    private val media: DraftMediaDataSource,
    private val notifications: SystemNotificationDataSource,
) : AccountDataLocalDataSource {
    override suspend fun deleteAccount(sessionId: String) {
        // Dismiss notifications even if a separate preference/file cleanup needs a later retry.
        var failure: Exception? = null
        try { notifications.deleteAccount(sessionId) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = error }
        try {
            val files = preferences.removeAccountData(sessionId)
            media.deleteAccount(sessionId, files)
            preferences.completeAccountMediaRemoval(sessionId)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (failure == null) failure = error }
        failure?.let { throw it }
    }
}
