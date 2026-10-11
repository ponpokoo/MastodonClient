package io.github.ponpokoo.mastodonclient.feature.compose

import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeSensitiveDefaultsTest : ScreenViewModelTestBase() {
    private fun model(repository: ScreenRepositoryFake, store: UserPreferencesStore, editId: String? = null) = own(
        ComposePostViewModel(null, editId, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, store, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
        }),
    )

    @Test fun localDefaultAppliesToFreshPostsAndManualChoiceSurvivesSettingChanges() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val store = UserPreferencesStore(MemoryAuthPreferences())
        store.setDefaultSensitive(testAccount.sessionId, true)
        val vm = model(repository, store); advanceUntilIdle()
        assertTrue(vm.uiState.value.sensitive)
        vm.onTextChanged("manual choice")
        vm.setSensitive(false)
        store.setDefaultSensitive(testAccount.sessionId, false)
        store.setDefaultSensitive(testAccount.sessionId, true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sensitive)
        vm.post(); advanceUntilIdle()
        assertFalse(posts.single().sensitive)
        val fresh = model(repository, store); advanceUntilIdle()
        assertTrue(fresh.uiState.value.sensitive)
        fresh.onTextChanged("default choice"); fresh.post(); advanceUntilIdle()
        assertTrue(posts.last().sensitive)
        store.setDefaultSensitive(testAccount.sessionId, false)
        val off = model(repository, store); advanceUntilIdle()
        assertFalse(off.uiState.value.sensitive)
    }

    @Test fun postingAccountSwitchUsesItsDefaultAndRestoresExistingInputInstead() = runTest(dispatcher) {
        val store = UserPreferencesStore(MemoryAuthPreferences())
        store.setDefaultSensitive(testAccount.sessionId, true)
        val vm = model(ScreenRepositoryFake(), store); advanceUntilIdle()
        assertTrue(vm.uiState.value.sensitive)
        vm.onTextChanged("kept input"); vm.setSensitive(false)
        vm.switchPostingAccount(secondAccount.sessionId); advanceUntilIdle()
        assertFalse(vm.uiState.value.sensitive)
        vm.switchPostingAccount(testAccount.sessionId); advanceUntilIdle()
        assertEquals("kept input", vm.uiState.value.text)
        assertFalse(vm.uiState.value.sensitive)
    }

    @Test fun restoredDraftsAndTemporaryInputsKeepBothFlagsAgainstOppositeDefaults() = runTest(dispatcher) {
        for (flag in listOf(false, true)) {
            val store = UserPreferencesStore(MemoryAuthPreferences())
            store.setDefaultSensitive(testAccount.sessionId, !flag)
            val vm = model(ScreenRepositoryFake(), store); advanceUntilIdle()
            val draft = ComposeDraft("draft", testAccount.sessionId, text = "saved input", sensitive = flag)
            store.saveDraft(draft)
            vm.restoreDraft(draft); advanceUntilIdle()
            assertEquals(flag, vm.uiState.value.sensitive)
            vm.retainInputThen {}
            val restored = model(ScreenRepositoryFake(), store); advanceUntilIdle()
            assertEquals("saved input", restored.uiState.value.text)
            assertEquals(flag, restored.uiState.value.sensitive)
        }
    }

    @Test fun savingDraftPreservesItsFlagAndStartsFreshInputWithLatestLocalDefault() = runTest(dispatcher) {
        val store = UserPreferencesStore(MemoryAuthPreferences())
        val vm = model(ScreenRepositoryFake(), store); advanceUntilIdle()
        vm.onTextChanged("saved without warning")
        assertFalse(vm.uiState.value.sensitive)
        store.setDefaultSensitive(testAccount.sessionId, true)
        vm.saveDraft(); advanceUntilIdle()
        assertFalse(store.drafts.first().single().sensitive)
        assertEquals("", vm.uiState.value.text)
        assertTrue(vm.uiState.value.sensitive)
    }

    @Test fun editingKeepsOriginalFlagAndSendsItAgainstOppositeLocalDefault() = runTest(dispatcher) {
        for (flag in listOf(false, true)) {
            val edits = mutableListOf<EditableStatus>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getEditableStatus(session: AccountSession, statusId: String) = Result.success(EditableStatus(statusId, "original", "", flag, null))
                override suspend fun updateStatus(session: AccountSession, status: EditableStatus): Result<TimelineStatus> {
                    edits += status
                    return Result.success(testStatus())
                }
            }
            val store = UserPreferencesStore(MemoryAuthPreferences())
            store.setDefaultSensitive(testAccount.sessionId, !flag)
            val vm = model(repository, store, "original"); advanceUntilIdle()
            assertEquals(flag, vm.uiState.value.sensitive)
            vm.post(); advanceUntilIdle()
            assertEquals(flag, edits.single().sensitive)
        }
    }
}
