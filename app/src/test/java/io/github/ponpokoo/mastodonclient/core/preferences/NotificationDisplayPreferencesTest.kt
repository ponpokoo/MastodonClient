package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationDisplayPreferencesTest {
    @Test fun existingPreferencesDefaultToIndividualAndSurviveGroupingWrites() = runTest {
        val data = MemoryAuthPreferences(emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark","openLinksInApp":false}"""
        })
        val store = UserPreferencesStore(data)
        assertFalse(store.preferences.first().groupNotifications)
        store.setGroupNotifications(true)
        val restored = UserPreferencesStore(data).preferences.first()
        assertTrue(restored.groupNotifications)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertFalse(restored.openLinksInApp)
    }

    @Test fun groupingIsGlobalAndSurvivesAccountRemovalAndOtherPreferenceChanges() = runTest {
        val data = MemoryAuthPreferences()
        val store = UserPreferencesStore(data)
        store.setGroupNotifications(true)
        store.setAccountPreferences("one", AccountPreferences(expandSpoilers = true))
        store.setAccountPreferences("two", AccountPreferences())
        store.removeAccountData("one")
        assertTrue(UserPreferencesStore(data).preferences.first().groupNotifications)
        store.setGroupNotifications(false)
        store.setThemeMode(ThemeMode.Dark)
        assertFalse(UserPreferencesStore(data).preferences.first().groupNotifications)
    }
}
