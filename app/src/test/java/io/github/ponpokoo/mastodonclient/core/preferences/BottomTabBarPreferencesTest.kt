package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BottomTabBarPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun existingSettingsDefaultToFloatingAndSelectedStyleSurvivesReopeningTheStore() = runBlocking {
        val file = folder.root.resolve("app.preferences_pb")
        suspend fun withStore(check: suspend (UserPreferencesStore) -> Unit) {
            val job = SupervisorJob()
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            try {
                check(UserPreferencesStore(data))
            } finally {
                job.cancelAndJoin()
            }
        }
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            data.updateData { previous ->
                previous.toMutablePreferences().apply {
                    this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark"}"""
                }
            }
        } finally {
            job.cancelAndJoin()
        }
        withStore { store ->
            assertEquals(BottomTabBarStyle.Floating, store.preferences.first().bottomTabBarStyle)
            store.setBottomTabBarStyle(BottomTabBarStyle.FullWidth)
        }
        withStore { store ->
            val preferences = store.preferences.first()
            assertEquals(BottomTabBarStyle.FullWidth, preferences.bottomTabBarStyle)
            assertEquals(ThemeMode.Dark, preferences.themeMode)
            store.setBottomTabBarStyle(BottomTabBarStyle.Floating)
        }
        withStore { store ->
            assertEquals(BottomTabBarStyle.Floating, store.preferences.first().bottomTabBarStyle)
        }
    }
}
