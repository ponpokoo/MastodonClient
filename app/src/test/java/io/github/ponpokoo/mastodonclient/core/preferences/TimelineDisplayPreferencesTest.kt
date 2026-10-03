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

    @Test fun newPreferencesUseStandardAndExplicitSmallSurvivesReload() = runTest {
        val dataStore = MemoryPreferences()
        val store = UserPreferencesStore(dataStore)
        assertEquals(ThumbnailSize.Standard, store.preferences.first().timelineDisplay.thumbnailSize)
        store.setTimelineDisplay(store.preferences.first().timelineDisplay.copy(thumbnailSize = ThumbnailSize.Compact))
        assertEquals(ThumbnailSize.Compact, UserPreferencesStore(dataStore).preferences.first().timelineDisplay.thumbnailSize)
    }

    @Test fun legacyLargeThumbnailsBecomeStandardWithoutResettingOtherSettings() = runTest {
        val key = stringPreferencesKey("app_preferences_v2")
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[key] = """{"themeMode":"Dark","altTextReminder":false,"timelineDisplay":{"thumbnailSize":"Large","fontSize":"ExtraLarge","showCounts":false,"avatarIconShape":"Square"},"accountPreferences":{"session":{"streaming":"Off"}}}"""
        }
        val dataStore = MemoryPreferences(initial)
        val store = UserPreferencesStore(dataStore)
        val restored = store.preferences.first()
        assertEquals(ThumbnailSize.Standard, restored.timelineDisplay.thumbnailSize)
        assertEquals(FontSizePreset.ExtraLarge, restored.timelineDisplay.fontSize)
        assertEquals(AvatarIconShape.Square, restored.timelineDisplay.avatarIconShape)
        assertEquals(false, restored.timelineDisplay.showCounts)
        assertEquals(false, restored.altTextReminder)
        assertEquals(ThemeMode.Dark, restored.themeMode)
        assertEquals(StreamingPolicy.Off, restored.forAccount("session").streaming)

        store.setOpenLinksInApp(false)
        val updated = UserPreferencesStore(dataStore).preferences.first()
        assertEquals(restored.copy(openLinksInApp = false), updated)
    }

    @Test fun bothThumbnailChoicesSurviveRecreationAndUpdates() = runTest {
        for (size in ThumbnailSize.entries) {
            val dataStore = MemoryPreferences()
            UserPreferencesStore(dataStore).setTimelineDisplay(TimelineDisplayPreferences(thumbnailSize = size))
            val restored = UserPreferencesStore(dataStore)
            assertEquals(size, restored.preferences.first().timelineDisplay.thumbnailSize)
            restored.setThemeMode(ThemeMode.Dark)
            assertEquals(size, UserPreferencesStore(dataStore).preferences.first().timelineDisplay.thumbnailSize)
        }
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
        assertEquals(ThumbnailSize.Standard, preferences.timelineDisplay.thumbnailSize)
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
