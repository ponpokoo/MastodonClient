package io.github.ponpokoo.mastodonclient

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class EmojiFavoritesStorageDeviceTest {
    @Test fun favoritesAndRemovalsSurviveClosingAndReopeningTheDiskStore() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "favorite-fixture-${UUID.randomUUID()}.preferences_pb")
        suspend fun openStore(action: suspend (UserPreferencesStore) -> Unit) {
            val job = SupervisorJob()
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            try { action(UserPreferencesStore(data)) } finally { job.cancelAndJoin() }
        }
        try {
            openStore {
                it.setEmojiFavorite("a", "😊", true)
                it.setEmojiFavorite("a", "custom", true)
                it.setEmojiFavorite("b", "👍", true)
            }
            openStore {
                assertEquals(mapOf("a" to listOf("😊", "custom"), "b" to listOf("👍")), it.emojiFavorites.first())
                it.setEmojiFavorite("a", "custom", false)
            }
            openStore {
                assertEquals(mapOf("a" to listOf("😊"), "b" to listOf("👍")), it.emojiFavorites.first())
                it.removeAccountData("a")
            }
            openStore { assertEquals(mapOf("b" to listOf("👍")), it.emojiFavorites.first()) }
        } finally { file.delete() }
    }
}
