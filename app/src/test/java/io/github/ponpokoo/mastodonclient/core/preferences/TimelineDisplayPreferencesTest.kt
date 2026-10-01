package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDisplayPreferencesTest {
    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    @Test fun legacyDisplaySettingsKeepTheirValuesAndUseCircularAvatars() = runTest {
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark","timelineDisplay":{"fontSize":"Large","avatarIconSize":"Small","showCounts":false}}"""
        }
        val preferences = UserPreferencesStore(MemoryPreferences(initial)).preferences.first()
        assertEquals(AvatarIconShape.Circle, preferences.timelineDisplay.avatarIconShape)
        assertEquals(FontSizePreset.Large, preferences.timelineDisplay.fontSize)
        assertEquals(AvatarIconSize.Small, preferences.timelineDisplay.avatarIconSize)
        assertEquals(false, preferences.timelineDisplay.showCounts)
        assertEquals(true, preferences.timelineDisplay.showReactions)
        assertEquals(ThemeMode.Dark, preferences.themeMode)
    }

    @Test fun squareChoiceSurvivesStoreRecreationAndOtherSettingChanges() = runTest {
        val dataStore = MemoryPreferences()
        val store = UserPreferencesStore(dataStore)
        store.setTimelineDisplay(TimelineDisplayPreferences(
            avatarIconShape = AvatarIconShape.Square, avatarIconSize = AvatarIconSize.Large,
        ))
        val restored = UserPreferencesStore(dataStore)
        restored.setThemeMode(ThemeMode.Dark)
        val preferences = UserPreferencesStore(dataStore).preferences.first()
        assertEquals(AvatarIconShape.Square, preferences.timelineDisplay.avatarIconShape)
        assertEquals(AvatarIconSize.Large, preferences.timelineDisplay.avatarIconSize)
        assertEquals(ThemeMode.Dark, preferences.themeMode)
    }

    @Test fun hiddenReactionsSurviveRecreationAndOtherSettingChanges() = runTest {
        val dataStore = MemoryPreferences()
        UserPreferencesStore(dataStore).setTimelineDisplay(TimelineDisplayPreferences(showReactions = false))
        val restored = UserPreferencesStore(dataStore)
        restored.setTimelineDisplay(restored.preferences.first().timelineDisplay.copy(showCounts = false))
        val display = UserPreferencesStore(dataStore).preferences.first().timelineDisplay
        assertEquals(false, display.showReactions)
        assertEquals(false, display.showCounts)
    }
}
