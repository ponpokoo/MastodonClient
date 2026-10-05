package io.github.ponpokoo.mastodonclient.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WordMuteRepositoryTest {
    private class Memory : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    @Test fun persistsPerAccountRejectsDuplicatesAndRestoresOrderWithoutResettingPreferences() = runTest {
        val memory = Memory(); val store = UserPreferencesStore(memory)
        val repository = DefaultWordMuteRepository(store, backgroundScope)
        repository.add("A", "  Spoiler  "); repository.add("A", "秘密"); repository.add("B", "セール")
        assertTrue(runCatching { repository.add("A", "spoiler") }.isFailure)
        assertTrue(runCatching { repository.add("A", "  ") }.isFailure)
        assertTrue(runCatching { repository.add("A", "a".repeat(101)) }.isFailure)
        repository.remove("A", "Spoiler"); repository.restore("A", "Spoiler", 0)
        store.setOpenLinksInApp(false)
        val restored = DefaultWordMuteRepository(UserPreferencesStore(memory), backgroundScope)
        runCurrent()
        assertEquals(listOf("Spoiler", "秘密"), restored.words.value["A"])
        assertEquals(listOf("セール"), restored.words.value["B"])
        assertEquals(false, store.openLinksInApp.first())
    }
}
