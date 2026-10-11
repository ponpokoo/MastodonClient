package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.MediaDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReadingPreferencesTest {
    @Test fun allLocalChoicesSurviveReloadAndPreserveOtherAccountAndAppPreferences() = runTest {
        val data = MemoryAuthPreferences()
        val store = UserPreferencesStore(data)
        store.setAccountPreferences("a", AccountPreferences(defaultVisibility = PostVisibility.Direct, streaming = StreamingPolicy.Off))
        store.setMediaDisplay("b", MediaDisplay.ShowAll)
        store.setExpandSpoilers("b", true)
        store.setDefaultSensitive("b", true)
        store.setThemeMode(ThemeMode.Dark)
        for (media in MediaDisplay.entries) {
            for (expand in listOf(true, false)) {
                store.setMediaDisplay("a", media)
                store.setExpandSpoilers("a", expand)
                store.setDefaultSensitive("a", !expand)
                val restored = UserPreferencesStore(data).preferences.first()
                assertEquals(AccountPreferences(PostVisibility.Direct, StreamingPolicy.Off, media, expand, !expand), restored.forAccount("a"))
                assertEquals(AccountPreferences(mediaDisplay = MediaDisplay.ShowAll, expandSpoilers = true, defaultSensitive = true), restored.forAccount("b"))
                assertEquals(ThemeMode.Dark, restored.themeMode)
            }
        }
    }

    @Test fun obsoleteServerReadingCacheIsIgnoredWithoutReplacingLocalSettingsOrResettingOtherData() = runTest {
        val data = MemoryAuthPreferences()
        val key = stringPreferencesKey("app_preferences_v2")
        data.updateData { it.toMutablePreferences().apply {
            this[key] = """{"themeMode":"Dark","accountPreferences":{"a":{"mediaDisplay":"HideAll","expandSpoilers":true}},"serverSettings":{"a":{"instanceUrl":"https://a.example/","accountId":"a","defaults":{"visibility":"private"},"reading":{"media":"ShowAll","expandSpoilers":false}}}}"""
        } }
        val store = UserPreferencesStore(data)
        val before = store.preferences.first()
        assertEquals(MediaDisplay.HideAll, before.forAccount("a").mediaDisplay)
        assertTrue(before.forAccount("a").expandSpoilers)
        assertEquals(AccountPreferences(), before.forAccount("new-account"))
        assertEquals("private", before.serverSettings.getValue("a").defaults?.visibility)
        store.setOpenLinksInApp(false)
        assertEquals(before.copy(openLinksInApp = false), UserPreferencesStore(data).preferences.first())
        assertFalse(data.data.first()[key]!!.contains("\"reading\""))
    }
}
