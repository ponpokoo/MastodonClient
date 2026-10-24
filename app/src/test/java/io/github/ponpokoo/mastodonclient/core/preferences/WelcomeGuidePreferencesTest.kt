package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WelcomeGuidePreferencesTest {
    @Test fun completionSurvivesReloadAndUnrelatedSettingsChanges() = runTest {
        val data = MemoryAuthPreferences()
        val store = UserPreferencesStore(data)
        assertFalse(store.preferences.first().welcomeGuideCompleted)
        store.completeWelcomeGuide()
        store.setThemeMode(ThemeMode.Dark)
        store.setTimelineDisplay(TimelineDisplayPreferences(fontSize = FontSizePreset.Large))
        val restored = UserPreferencesStore(data).preferences.first()
        assertTrue(restored.welcomeGuideCompleted)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertEquals(FontSizePreset.Large, restored.timelineDisplay.fontSize)
    }

    @Test fun existingPreferencesWithoutGuideStateRemainIntact() = runTest {
        val data = MemoryAuthPreferences(emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark","openLinksInApp":false}"""
        })
        val store = UserPreferencesStore(data)
        assertFalse(store.preferences.first().welcomeGuideCompleted)
        store.completeWelcomeGuide()
        val restored = UserPreferencesStore(data).preferences.first()
        assertTrue(restored.welcomeGuideCompleted)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertFalse(restored.openLinksInApp)
    }
}
