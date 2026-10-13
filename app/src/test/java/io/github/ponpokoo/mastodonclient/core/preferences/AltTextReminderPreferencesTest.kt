package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.ServerAccountSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AltTextReminderPreferencesTest {
    @Test fun absentReminderUsesOffAndKeepsOtherLegacySettingsAfterUpdate() = runTest {
        val data = MemoryAuthPreferences(emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark"}"""
        })
        val store = UserPreferencesStore(data)
        assertFalse(store.preferences.first().altTextReminder)
        assertEquals(ThemeMode.Dark, store.preferences.first().themeMode)
        store.setOpenLinksInApp(false)
        val restored = UserPreferencesStore(data).preferences.first()
        assertFalse(restored.altTextReminder)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertFalse(restored.openLinksInApp)
    }

    @Test fun explicitReminderChoicesSurviveReloadAndServerCacheWrites() = runTest {
        val data = MemoryAuthPreferences()
        for (enabled in listOf(true, false)) {
            val store = UserPreferencesStore(data)
            store.setAltTextReminder(enabled)
            store.saveServerSettings("session", ServerAccountSettings("https://example.social", "opaque-id"))
            store.setThemeMode(ThemeMode.Dark)
            assertEquals(enabled, UserPreferencesStore(data).preferences.first().altTextReminder)
        }
    }
}
