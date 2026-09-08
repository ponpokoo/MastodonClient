package io.github.ponpokoo.mastodonclient.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class UserPreferencesStore(context: Context) {
    private val dataStore = context.applicationContext.userPreferencesDataStore

    val openLinksInApp: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[OPEN_LINKS_IN_APP] ?: true
    }

    suspend fun setOpenLinksInApp(enabled: Boolean) {
        dataStore.edit { it[OPEN_LINKS_IN_APP] = enabled }
    }

    private companion object {
        val OPEN_LINKS_IN_APP = booleanPreferencesKey("open_links_in_app")
    }
}
