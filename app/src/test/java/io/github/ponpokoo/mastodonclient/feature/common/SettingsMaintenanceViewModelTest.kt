package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMaintenanceViewModelTest : ScreenViewModelTestBase() {
    @Test fun duplicateClearIsIgnoredWhilePending() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val model = own(SettingsMaintenanceViewModel(fakeRepository { calls++; pending.await() }))
        model.clearCache()
        model.clearCache()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(model.cacheState.value.isClearing)
        assertNull(model.cacheState.value.message)
        pending.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.cacheState.value.isClearing)
        assertEquals("画像キャッシュを削除しました。", model.cacheState.value.message)
    }

    @Test fun failureCanBeRetriedAndVersionComesFromRepository() = runTest(dispatcher) {
        var attempts = 0
        val model = own(SettingsMaintenanceViewModel(fakeRepository {
            if (++attempts == 1) error("disk unavailable")
        }))
        assertEquals("1.2.3", model.versionName)
        assertEquals(42L, model.versionCode)
        model.clearCache()
        advanceUntilIdle()
        assertFalse(model.cacheState.value.isClearing)
        assertTrue(model.cacheState.value.message!!.contains("削除できませんでした"))
        model.clearCache()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals("画像キャッシュを削除しました。", model.cacheState.value.message)
    }

    private fun fakeRepository(clear: suspend () -> Unit) = object : AppMaintenanceRepository {
        override val versionName = "1.2.3"
        override val versionCode = 42L
        override suspend fun clearImageCache() = clear()
    }
}
