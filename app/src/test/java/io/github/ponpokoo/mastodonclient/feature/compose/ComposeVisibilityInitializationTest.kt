package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AccountPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeVisibilityInitializationTest : ScreenViewModelTestBase() {
    private class MemoryPreferences : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        var readGate: CompletableDeferred<Unit>? = null
        override val data = flow {
            readGate?.await()
            emitAll(values)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(values.value).also { values.value = it }
    }

    private fun model(repository: ScreenRepositoryFake, preferences: UserPreferencesStore) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
        }),
    )

    @Test fun localVisibilityIsHiddenUntilSavedPreferencesLoadAndDoesNotWaitForConfiguration() = runTest(dispatcher) {
        val dataStore = MemoryPreferences()
        val preferences = UserPreferencesStore(dataStore)
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = PostVisibility.Unlisted))
        val savedPreferences = CompletableDeferred<Unit>()
        dataStore.readGate = savedPreferences
        val configuration = CompletableDeferred<Result<ComposerConfiguration>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getComposerConfiguration(session: AccountSession) = configuration.await()
            override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> =
                error("Opening the composer must not fetch posting defaults")
        }
        val vm = model(repository, preferences)
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        runCurrent()
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        savedPreferences.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
        assertTrue(vm.uiState.value.isLoading)
        configuration.complete(Result.success(ComposerConfiguration()))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
    }

    @Test fun savedServerVisibilityIsHiddenUntilLocalCacheIsPrepared() = runTest(dispatcher) {
        val savedSettings = CompletableDeferred<Unit>()
        val settings = MutableStateFlow<Map<String, ServerAccountSettings>>(emptyMap())
        val repository = object : ScreenRepositoryFake() {
            override val serverSettings = settings
            override suspend fun prepareServerSettings(session: AccountSession) {
                savedSettings.await()
                settings.value = mapOf(session.sessionId to ServerAccountSettings(
                    session.instanceUrl, session.accountId, defaults = PostingDefaults("private"),
                ))
            }
            override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> =
                error("Opening the composer must only prepare saved settings")
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = PostVisibility.Unlisted))
        val vm = model(repository, preferences)
        runCurrent()
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        savedSettings.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.FollowersOnly, vm.uiState.value.visibility)
    }

    @Test fun bufferVisibilityAppearsBeforeNetworkCompletesAndManualChoiceIsRetained() = runTest(dispatcher) {
        val configuration = CompletableDeferred<Result<ComposerConfiguration>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getComposerConfiguration(session: AccountSession) = configuration.await()
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = PostVisibility.Unlisted))
        preferences.retainComposeBuffer(ComposeDraft("${testAccount.sessionId}:new:", testAccount.sessionId,
            text = "buffered", visibility = PostVisibility.Direct))
        val vm = model(repository, preferences)
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        runCurrent()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertTrue(vm.uiState.value.isLoading)
        assertEquals("buffered", vm.uiState.value.text)
        assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
        vm.setVisibility(PostVisibility.Public)
        configuration.complete(Result.success(ComposerConfiguration()))
        advanceUntilIdle()
        assertEquals(PostVisibility.Public, vm.uiState.value.visibility)
        assertTrue(vm.uiState.value.isVisibilityInitialized)
    }

    @Test fun accountSwitchHidesPreviousVisibilityUntilSelectedAccountsSavedValueIsReady() = runTest(dispatcher) {
        val secondSettings = CompletableDeferred<Unit>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun prepareServerSettings(session: AccountSession) {
                if (session.sessionId == secondAccount.sessionId) secondSettings.await()
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = PostVisibility.Unlisted))
        preferences.setAccountPreferences(secondAccount.sessionId, AccountPreferences(defaultVisibility = PostVisibility.FollowersOnly))
        val vm = model(repository, preferences)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
        vm.switchPostingAccount(secondAccount.sessionId)
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        runCurrent()
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        secondSettings.complete(Unit)
        advanceUntilIdle()
        assertEquals(secondAccount, vm.uiState.value.selectedSession)
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.FollowersOnly, vm.uiState.value.visibility)
        vm.onTextChanged("saved")
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.text)
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.FollowersOnly, vm.uiState.value.visibility)
    }
}
