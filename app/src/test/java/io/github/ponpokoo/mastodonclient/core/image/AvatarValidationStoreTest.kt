package io.github.ponpokoo.mastodonclient.core.image

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AvatarValidationStoreTest {
    @Test fun urlMetadataSurvivesRecreationAndClearPreservesOtherPreferences() = runTest {
        val backing = MemoryPreferences()
        backing.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("theme")] = "dark" } }
        val store = PreferencesAvatarValidationStore(backing)
        val url = "https://remote.example/avatar.png?version=1"
        store.write(url, AvatarValidation(100, 200))
        val restored = PreferencesAvatarValidationStore(backing)
        assertEquals(AvatarValidation(100, 200), restored.read(url))
        assertEquals(AvatarValidation(), restored.read("https://other.example/avatar.png?version=1"))
        assertFalse(backing.data.value.toString().contains(url))
        restored.clear()
        assertEquals(AvatarValidation(), store.read(url))
        assertEquals("dark", backing.data.value[stringPreferencesKey("theme")])
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
