package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.data.local.AccountDataLocalDataSource
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.PushControlRepository
import io.github.ponpokoo.mastodonclient.domain.repository.PushControlState
import io.github.ponpokoo.mastodonclient.feature.settings.PushSettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountCleanupForegroundTest : ScreenViewModelTestBase() {
    @Test fun foregroundRetriesFailedRemovalEvenWithNoPushSubscriptions() = runTest(dispatcher) {
        val store = memoryAuthStore(MemoryAuthPreferences())
        store.saveSession(testAccount)
        var fail = true
        var cleaned = 0
        val auth = DefaultAuthRepository(ApiClientFactory(), store, accountData = AccountDataLocalDataSource {
            if (fail) throw java.io.IOException("disk unavailable")
            cleaned++
        })
        assertTrue(auth.logout(testAccount))
        val push = object : PushControlRepository {
            override val states = MutableStateFlow(emptyMap<String, PushControlState>())
            override suspend fun refresh() = Unit
            override suspend fun setEnabled(sessionId: String, enabled: Boolean) = Unit
            override suspend fun tokenChanged(token: String?) = Unit
        }
        val model = own(PushSettingsViewModel(push, auth))
        model.setForeground(true); runCurrent()
        assertEquals(setOf(testAccount.sessionId), store.pendingAccountDeletions())
        model.setForeground(false); runCurrent()
        fail = false
        model.setForeground(true); runCurrent()
        assertEquals(1, cleaned)
        assertTrue(store.pendingAccountDeletions().isEmpty())
        assertNull(store.getSession())
        model.setForeground(false)
    }
}
