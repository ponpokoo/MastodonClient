package io.github.ponpokoo.mastodonclient.core.preferences

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FabPositionPreferencesTest {
    @Test fun legacySettingsAndAccountChangesKeepGlobalPosition() = runTest {
        val data = MemoryAuthPreferences(emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark"}"""
        })
        val store = UserPreferencesStore(data)
        assertNull(store.fabPosition.first())
        store.setFabPosition(FabPosition(-1f, 2f))
        store.setAccountPreferences("account-b", AccountPreferences())
        val restored = UserPreferencesStore(data)
        assertEquals(FabPosition(0f, 1f), restored.fabPosition.first())
        assertEquals(ThemeMode.Dark, restored.preferences.first().themeMode)
        restored.resetFabPosition()
        assertNull(UserPreferencesStore(data).fabPosition.first())
    }

    @Test fun invalidPositionDoesNotOverwriteSavedValue() = runTest {
        val store = UserPreferencesStore(MemoryAuthPreferences())
        store.setFabPosition(FabPosition(.2f, .7f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                store.setFabPosition(FabPosition(invalid, .5f))
                fail("Non-finite position accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(FabPosition(.2f, .7f), store.fabPosition.first())
    }
}
