package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAuthRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.common.AccountRemovalButton
import io.github.ponpokoo.mastodonclient.feature.common.AccountSwitchDialog
import io.github.ponpokoo.mastodonclient.feature.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountManagementDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val alice = AccountSession("one", "https://one.example", "a", "alice", "Alice", "", "synthetic-a")
    private val bob = AccountSession("two", "https://two.example", "b", "bob", "Bob", "", "synthetic-b")
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    @Test fun cancelKeepsRegistrationAndConfirmRemovesOnlyDisplayedAccount() {
        val data = MemoryPreferences(); val store = SecureAuthStore(data, { it }, { it })
        runBlocking { store.saveSession(alice); store.saveSession(bob) }
        val repository = DefaultAuthRepository(ApiClientFactory(), store)
        var removals = 0
        compose.setContent { MaterialTheme {
            AccountRemovalButton(bob, { expected -> runBlocking { assertTrue(repository.logout(expected)) }; removals++ })
        } }
        compose.onNodeWithTag("remove_account").performClick()
        compose.onNodeWithText("Bob", substring = true).assertIsDisplayed()
        compose.onNodeWithText("https://two.example").assertIsDisplayed()
        compose.onNodeWithText("キャンセル").performClick()
        compose.runOnIdle {
            assertEquals(0, removals)
            runBlocking { assertEquals(listOf(alice, bob), store.getSessions()); assertEquals(bob, store.getSession()) }
        }
        compose.onNodeWithTag("remove_account").performClick()
        compose.onNodeWithTag("confirm_account_removal").performClick()
        compose.runOnIdle {
            assertEquals(1, removals)
            runBlocking { assertEquals(listOf(alice), store.getSessions()); assertEquals(alice, store.getSession()) }
        }
    }

    @Test fun switchingAccountWhileConfirmingRequiresNewConfirmation() {
        val account = mutableStateOf(alice)
        val removed = mutableListOf<AccountSession>()
        compose.setContent { MaterialTheme { AccountRemovalButton(account.value, { removed += it }) } }
        compose.onNodeWithTag("remove_account").performClick()
        compose.runOnIdle { account.value = bob }
        compose.onNodeWithTag("confirm_account_removal").assertDoesNotExist()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithTag("remove_account").performClick()
        compose.onNodeWithText("Bob", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("confirm_account_removal").performClick()
        compose.runOnIdle { assertEquals(listOf(bob), removed) }
    }

    @Test fun metadataUpdateKeepsConfirmationAndBusyPreventsDuplicateRemoval() {
        val account = mutableStateOf(alice); val busy = mutableStateOf(false)
        var removals = 0
        compose.setContent { MaterialTheme { AccountRemovalButton(account.value, { removals++ }, enabled = !busy.value) } }
        compose.onNodeWithTag("remove_account").performClick()
        val updated = alice.copy(displayName = "Alice updated", avatarRevision = 1)
        compose.runOnIdle { account.value = updated; busy.value = true }
        compose.onNodeWithText(updated.displayName, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("confirm_account_removal").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, removals); busy.value = false }
        compose.onNodeWithTag("confirm_account_removal").performClick()
        compose.onNodeWithTag("confirm_account_removal").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test fun settingsReorderPersistsAndAccountPickerUsesSameOrderAndSelection() {
        val data = MemoryPreferences(); val store = SecureAuthStore(data, { it }, { it })
        runBlocking { store.saveSession(alice); store.saveSession(bob) }
        val repository = DefaultAuthRepository(ApiClientFactory(), store)
        val preferences = UserPreferencesStore(MemoryPreferences())
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"; override val versionCode = 1L
            override suspend fun clearImageCache() { }
        })
        val showPicker = mutableStateOf(false)
        var selected: String? = null
        compose.setContent {
            val accounts by repository.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
            MaterialTheme {
                SettingsPageContent(SettingsPage.Accounts, {}, maintenance, preferences, bob, accounts, {}, {}, {},
                    onMoveAccount = { id, before -> runBlocking { repository.moveAccount(id, before).getOrThrow() } })
                if (showPicker.value) AccountSwitchDialog("アカウント切替", accounts, bob.sessionId,
                    { selected = it; showPicker.value = false }, { showPicker.value = false })
            }
        }
        val bobHandle = compose.onNodeWithTag("reorder_handle_two")
        val aliceHandle = compose.onNodeWithTag("reorder_handle_one")
        val delta = aliceHandle.fetchSemanticsNode().boundsInRoot.center.y - bobHandle.fetchSemanticsNode().boundsInRoot.center.y
        bobHandle.performTouchInput { swipe(center, center + Offset(0f, delta), 500) }
        val bobRow = compose.onNodeWithTag("registered_account_two").fetchSemanticsNode().boundsInRoot
        val aliceRow = compose.onNodeWithTag("registered_account_one").fetchSemanticsNode().boundsInRoot
        assertTrue(bobRow.top < aliceRow.top)
        compose.runOnIdle {
            runBlocking {
                val recreated = SecureAuthStore(data, { it }, { it })
                assertEquals(listOf(bob, alice), recreated.getSessions()); assertEquals(bob, recreated.getSession())
            }
            showPicker.value = true
        }
        compose.onNodeWithTag("active_account_switch").assert(hasText("Bob"))
        val bobInPicker = compose.onNode(hasText("Bob") and hasAnyAncestor(hasTestTag("account_switch_dialog")))
        val aliceInPicker = compose.onNode(hasText("Alice") and hasAnyAncestor(hasTestTag("account_switch_dialog")))
        val pickerBob = bobInPicker.fetchSemanticsNode().boundsInRoot
        val pickerAlice = aliceInPicker.fetchSemanticsNode().boundsInRoot
        assertTrue(pickerBob.top < pickerAlice.top)
        aliceInPicker.performClick()
        compose.runOnIdle { assertEquals(alice.sessionId, selected) }
    }
}
