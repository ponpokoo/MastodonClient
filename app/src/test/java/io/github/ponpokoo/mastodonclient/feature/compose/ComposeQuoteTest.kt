package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeQuoteTest : ScreenViewModelTestBase() {
    private val quoted = testStatus("quoted").copy(
        quoteApproval = "automatic", url = "https://one.example/@author/quoted",
    )
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    private fun model(repository: ScreenRepositoryFake, preferences: UserPreferencesStore, mode: QuoteMode? = null) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(uris: List<String>) = Result.success(emptyList<DraftAttachment>())
        }, initialQuoteStatusId = quoted.statusId.takeIf { mode != null },
            initialQuoteStatusUrl = quoted.url.takeIf { mode != null }, nativeQuote = mode == QuoteMode.Native,
            logMediaFailure = {}),
    )

    @Test fun selectedQuoteModeSurvivesDraftRestoreAndControlsPostRequest() = runTest(dispatcher) {
        for (mode in QuoteMode.entries) {
            val posts = mutableListOf<CreateStatusRequest>()
            val repository = object : ScreenRepositoryFake() {
                override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
                override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                    posts += request
                    return Result.success(testStatus())
                }
            }
            val preferences = UserPreferencesStore(MemoryPreferences())
            val vm = model(repository, preferences, mode)
            advanceUntilIdle()
            assertEquals(quoted, vm.uiState.value.quoteToStatus)
            assertEquals(if (mode == QuoteMode.Native) "" else quoted.url, vm.uiState.value.text)
            val text = if (mode == QuoteMode.Native) "comment without URL" else "comment ${quoted.url}"
            vm.onTextChanged(text)
            vm.saveDraft()
            advanceUntilIdle()

            val restored = model(repository, preferences)
            advanceUntilIdle()
            restored.restoreDraft(preferences.drafts.first().single())
            advanceUntilIdle()
            assertEquals(mode == QuoteMode.Native, restored.uiState.value.quotingNative)
            restored.post()
            advanceUntilIdle()
            assertEquals(text, posts.single().text)
            assertEquals(quoted.statusId.takeIf { mode == QuoteMode.Native }, posts.single().quotedStatusId)
            assertNull(posts.single().replyToId)
        }
    }

    @Test fun nativeAndLinkQuotesKeepSeparateInputsAndSavedDrafts() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val native = model(repository, preferences, QuoteMode.Native)
        advanceUntilIdle()
        native.onTextChanged("native comment")
        native.saveDraft()
        advanceUntilIdle()
        native.retainInputThen {}

        val link = model(repository, preferences, QuoteMode.Link)
        advanceUntilIdle()
        assertEquals(quoted.url, link.uiState.value.text)
        val linkText = "link comment ${quoted.url}"
        link.onTextChanged(linkText)
        link.saveDraft()
        advanceUntilIdle()
        link.retainInputThen {}
        assertEquals(2, preferences.drafts.first().size)

        val reopenedNative = model(repository, preferences, QuoteMode.Native)
        val reopenedLink = model(repository, preferences, QuoteMode.Link)
        advanceUntilIdle()
        assertEquals("native comment", reopenedNative.uiState.value.text)
        assertTrue(reopenedNative.uiState.value.quotingNative)
        assertEquals(linkText, reopenedLink.uiState.value.text)
        assertFalse(reopenedLink.uiState.value.quotingNative)
    }

    @Test fun deletingQuoteUrlClearsPreviewAndDraftReferenceWithoutRestoringWhenRetyped() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences, QuoteMode.Link)
        advanceUntilIdle()
        vm.onTextChanged("${quoted.url}、comment https://other.example/post")
        assertEquals(quoted.statusId, vm.uiState.value.quoteStatusId)
        vm.retainInputThen {}
        vm.onTextChanged("comment https://other.example/post")
        assertNull(vm.uiState.value.quoteStatusId)
        assertNull(vm.uiState.value.quoteToStatus)
        vm.onTextChanged("comment ${quoted.url}")
        assertNull(vm.uiState.value.quoteStatusId)
        vm.saveDraft()
        advanceUntilIdle()
        val draft = preferences.drafts.first().single()
        assertNull(draft.quotedStatusId)
        assertNull(draft.quotedStatusUrl)
        assertFalse(draft.nativeQuote)
        vm.post()
        advanceUntilIdle()
        assertNull(posts.single().quotedStatusId)
        assertEquals("comment ${quoted.url}", posts.single().text)
        val reopened = model(repository, preferences, QuoteMode.Link)
        advanceUntilIdle()
        assertEquals(quoted.url, reopened.uiState.value.text)
    }

    @Test fun replacingQuoteUrlWithAnotherPostDetachesIt() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), QuoteMode.Link)
        advanceUntilIdle()
        vm.onTextChanged("${quoted.url}-other")
        assertNull(vm.uiState.value.quoteStatusId)
        assertEquals("${quoted.url}-other", vm.uiState.value.text)
    }

    @Test fun clearingNativeQuoteKeepsContentAndRemovesConstraintsAndOutgoingReference() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences, QuoteMode.Native)
        advanceUntilIdle()
        vm.restoreDraft(ComposeDraft(
            "native", testAccount.sessionId, quotedStatusId = quoted.statusId,
            quotedStatusUrl = quoted.url, nativeQuote = true, text = "comment ${quoted.url}",
            spoilerText = "CW", language = "ja", attachmentUris = listOf("file:///photo.jpg"),
        ))
        advanceUntilIdle()
        vm.onTextChanged("comment")
        assertTrue(vm.uiState.value.quotingNative)
        vm.clearQuote()
        assertEquals("comment", vm.uiState.value.text)
        assertEquals("CW", vm.uiState.value.spoilerText)
        assertEquals("ja", vm.uiState.value.language)
        assertEquals(listOf("file:///photo.jpg"), vm.uiState.value.attachments.map { it.uri })
        assertNull(vm.uiState.value.quoteToStatus)
        assertFalse(vm.uiState.value.quotingNative)
        vm.removeAttachment("file:///photo.jpg")
        vm.enablePoll()
        vm.setPollOption(0, "one")
        vm.setPollOption(1, "two")
        vm.post()
        advanceUntilIdle()
        assertNull(posts.single().quotedStatusId)
        assertEquals(listOf("one", "two"), posts.single().pollOptions)
    }

    @Test fun lateQuoteCannotReturnAfterUrlDeletionAndAccountSwitch() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                withContext(NonCancellable) { target.await() }
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> = error("No conversation fetch")
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), QuoteMode.Link)
        runCurrent()
        assertNull(vm.uiState.value.quoteToStatus)
        vm.onTextChanged("ordinary comment")
        vm.switchPostingAccount(secondAccount.sessionId)
        runCurrent()
        target.complete(Result.success(quoted))
        advanceUntilIdle()
        assertEquals(secondAccount, vm.uiState.value.selectedSession)
        assertNull(vm.uiState.value.quoteStatusId)
        assertNull(vm.uiState.value.quoteToStatus)
    }

    @Test fun lateQuoteCannotReplaceRestoredOrdinaryDraft() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                withContext(NonCancellable) { target.await() }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), QuoteMode.Native)
        runCurrent()
        vm.restoreDraft(ComposeDraft(
            "ordinary", testAccount.sessionId, text = "ordinary comment",
        ))
        runCurrent()
        target.complete(Result.success(quoted))
        advanceUntilIdle()
        assertNull(vm.uiState.value.quoteStatusId)
        assertNull(vm.uiState.value.quoteToStatus)
        assertFalse(vm.uiState.value.quotingNative)
        assertEquals("ordinary comment", vm.uiState.value.text)
    }

    @Test fun clearingQuoteBeforeConfigurationLoadsPreventsInitialPreviewAndUrlInsertion() = runTest(dispatcher) {
        val configuration = CompletableDeferred<Result<ComposerConfiguration>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getComposerConfiguration(session: AccountSession) = configuration.await()
            override fun getCachedStatus(session: AccountSession, statusId: String) = quoted
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), QuoteMode.Link)
        runCurrent()
        vm.clearQuote()
        configuration.complete(Result.success(ComposerConfiguration()))
        advanceUntilIdle()
        assertNull(vm.uiState.value.quoteStatusId)
        assertNull(vm.uiState.value.quoteToStatus)
        assertEquals("", vm.uiState.value.text)
    }

    @Test fun clearingNativeQuoteWhileFetchingIgnoresLateFailure() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                withContext(NonCancellable) { target.await() }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), QuoteMode.Native)
        runCurrent()
        vm.clearQuote()
        target.complete(Result.failure(IllegalStateException("old request failed")))
        advanceUntilIdle()
        assertNull(vm.uiState.value.quoteStatusId)
        assertNull(vm.uiState.value.quoteToStatus)
        assertNull(vm.uiState.value.errorMessage)
    }
}
