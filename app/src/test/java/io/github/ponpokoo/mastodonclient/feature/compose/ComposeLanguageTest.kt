package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.preferences.ComposerAction
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeLanguageTest : ScreenViewModelTestBase() {
    private class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    private fun model(repository: ScreenRepositoryFake, preferences: UserPreferencesStore, editId: String? = null) = own(
        ComposePostViewModel(null, editId, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult())
        }),
    )

    @Test fun selectedLanguageSurvivesDraftRestoreAndReachesPostRequest() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences)
        advanceUntilIdle()
        vm.onTextChanged("hello")
        vm.setLanguage("en")
        vm.saveDraft()
        advanceUntilIdle()
        val restored = model(repository, preferences)
        advanceUntilIdle()
        restored.restoreDraft(preferences.drafts.first().single())
        advanceUntilIdle()
        assertEquals("en", restored.uiState.value.language)
        restored.post()
        advanceUntilIdle()
        assertEquals("en", posts.single().language)
    }

    @Test fun defaultOmitsLanguageAndAccountSwitchDoesNotLeakChoice() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        vm.onTextChanged("日本語")
        vm.setLanguage("ja")
        vm.switchPostingAccount(secondAccount.sessionId)
        advanceUntilIdle()
        assertNull(vm.uiState.value.language)
        vm.switchPostingAccount(testAccount.sessionId)
        advanceUntilIdle()
        assertEquals("ja", vm.uiState.value.language)
        vm.setLanguage(null)
        vm.post()
        advanceUntilIdle()
        assertNull(posts.single().language)
    }

    @Test fun editingLoadsOriginalLanguageAndSendsChosenLanguage() = runTest(dispatcher) {
        val edits = mutableListOf<EditableStatus>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getEditableStatus(session: AccountSession, statusId: String) =
                Result.success(EditableStatus(statusId, "hello", "", false, "en"))
            override suspend fun updateStatus(session: AccountSession, status: EditableStatus): Result<TimelineStatus> {
                edits += status
                return Result.success(testStatus())
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), "post")
        advanceUntilIdle()
        assertEquals("en", vm.uiState.value.language)
        vm.setLanguage("ja")
        vm.post()
        advanceUntilIdle()
        assertEquals("ja", edits.single().language)
    }

    @Test fun addsNewButtonsToSavedOrderWithoutResettingOrderOrHiddenButtons() = runTest {
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"composerActionOrder":["DeleteDraft","Media","Poll","Emoji","ContentWarning","Mention","SaveDraft"],"hiddenComposerActions":["Poll"]}"""
        }
        val preferences = UserPreferencesStore(MemoryPreferences(initial)).preferences.first()
        assertEquals(ComposerAction.DeleteDraft, preferences.composerActionOrder.first())
        assertEquals(ComposerAction.entries.toSet(), preferences.composerActionOrder.toSet())
        assertEquals(setOf(ComposerAction.Poll), preferences.hiddenComposerActions)
    }

    @Test fun removedKanaActionDoesNotResetPreferencesWhenReadingOrUpdating() = runTest {
        val initial = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("app_preferences_v2")] = """{"themeMode":"Dark","altTextReminder":false,"composerActionOrder":["DeleteDraft","HalfWidthKana","Media","Poll","Emoji","ContentWarning","Mention","Language","Hashtag","SaveDraft"],"hiddenComposerActions":["HalfWidthKana","Poll"]}"""
        }
        val store = UserPreferencesStore(MemoryPreferences(initial))
        val preferences = store.preferences.first()
        assertEquals(ThemeMode.Dark, preferences.themeMode)
        assertFalse(preferences.altTextReminder)
        assertEquals(ComposerAction.DeleteDraft, preferences.composerActionOrder.first())
        assertEquals(ComposerAction.entries.toSet(), preferences.composerActionOrder.toSet())
        assertEquals(setOf(ComposerAction.Poll), preferences.hiddenComposerActions)

        store.setThemeMode(ThemeMode.Light)
        val updated = store.preferences.first()
        assertEquals(ThemeMode.Light, updated.themeMode)
        assertFalse(updated.altTextReminder)
        assertEquals(preferences.composerActionOrder, updated.composerActionOrder)
        assertEquals(preferences.hiddenComposerActions, updated.hiddenComposerActions)
    }
}
