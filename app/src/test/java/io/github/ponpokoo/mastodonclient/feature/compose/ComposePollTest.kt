package io.github.ponpokoo.mastodonclient.feature.compose

import io.github.ponpokoo.mastodonclient.core.preferences.*
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposePollTest : ScreenViewModelTestBase() {
    private fun model(repository: ScreenRepositoryFake, preferences: UserPreferencesStore) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
        }, logMediaFailure = {}),
    )

    @Test fun limitsValidateOptionsDurationAndUnicodeWithoutDeletingInput() {
        val limits = PollConfiguration(3, 2, 60, 3600, true)
        assertNull(limits.validationError(listOf("😀😀", "ok"), 60))
        assertNotNull(limits.validationError(listOf("a", "b", "c", "d"), 60))
        assertNotNull(limits.validationError(listOf("abc", "b"), 60))
        assertNotNull(limits.validationError(listOf("a", " "), 60))
        assertNotNull(limits.validationError(listOf("a", "b"), 3601))
    }

    @Test fun pollSettingsSurviveDraftRestoreAndReachSubmission() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getComposerConfiguration(session: AccountSession) = Result.success(
                ComposerConfiguration(polls = PollConfiguration(3, 10, 60, 3600, true)))
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryAuthPreferences())
        val vm = model(repository, preferences)
        advanceUntilIdle()
        vm.onTextChanged("question")
        vm.enablePoll()
        assertEquals(3600L, vm.uiState.value.pollExpiresInSeconds)
        vm.setPollOption(0, "apple")
        vm.setPollOption(1, "orange")
        vm.addPollOption()
        vm.setPollOption(2, "grape")
        vm.addPollOption()
        assertEquals(3, vm.uiState.value.pollOptions.size)
        vm.removePollOption(1)
        vm.removePollOption(0)
        assertEquals(listOf("apple", "grape"), vm.uiState.value.pollOptions)
        vm.setPollDuration(60)
        vm.setPollMultiple(true)
        vm.setPollHideTotals(true)
        vm.saveDraft()
        advanceUntilIdle()
        val draft = preferences.drafts.first().single()
        assertTrue(draft.pollHideTotals)
        val restored = model(repository, preferences)
        advanceUntilIdle()
        restored.restoreDraft(draft)
        advanceUntilIdle()
        assertEquals(60L, restored.uiState.value.pollExpiresInSeconds)
        assertTrue(restored.uiState.value.pollHideTotals)
        restored.setPollDuration(3601)
        restored.post()
        advanceUntilIdle()
        assertTrue(posts.isEmpty())
        assertNotNull(restored.uiState.value.errorMessage)
        assertEquals(listOf("apple", "grape"), restored.uiState.value.pollOptions)
        restored.setPollDuration(60)
        restored.post()
        advanceUntilIdle()
        assertTrue(posts.single().pollHideTotals)
        assertTrue(posts.single().pollMultiple)
        assertEquals(60L, posts.single().pollExpiresInSeconds)
    }
}
