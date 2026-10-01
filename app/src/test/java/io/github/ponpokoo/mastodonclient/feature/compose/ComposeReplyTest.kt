package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeReplyTest : ScreenViewModelTestBase() {
    private val parent = testStatus("parent").copy(author = StatusAuthor("alice", "Alice", "alice@example.org", ""))
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private fun model(repository: ScreenRepositoryFake, preferences: UserPreferencesStore, replyId: String? = "parent") = own(
        ComposePostViewModel(replyId, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(uris: List<String>) = Result.success(emptyList<DraftAttachment>())
        }, logMediaFailure = {}),
    )

    @Test fun switchingAccountKeepsMentionTextButPostsWithoutReplyTarget() = runTest(dispatcher) {
        val posts = mutableListOf<Pair<AccountSession, CreateStatusRequest>>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String): TimelineStatus {
                assertEquals(testAccount, session)
                return parent
            }
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += session to request
                return Result.success(testStatus())
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        val text = "@alice@example.org hello"
        vm.onTextChanged(text)
        vm.switchPostingAccount(testAccount.sessionId)
        assertEquals("parent", vm.uiState.value.replyToId)
        vm.switchPostingAccount(secondAccount.sessionId)
        advanceUntilIdle()
        assertEquals(secondAccount, vm.uiState.value.selectedSession)
        assertEquals(text, vm.uiState.value.text)
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        vm.post()
        advanceUntilIdle()
        assertEquals(secondAccount, posts.single().first)
        assertEquals(text, posts.single().second.text)
        assertNull(posts.single().second.replyToId)
    }

    @Test fun cachedReplyAppearsBeforeConfigurationAndNeverFetchesConversation() = runTest(dispatcher) {
        val configuration = CompletableDeferred<Result<ComposerConfiguration>>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = parent
            override suspend fun getComposerConfiguration(session: AccountSession) = configuration.await()
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String): Result<TimelineStatus> = error("Already cached")
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> = error("No conversation fetch")
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(parent, vm.uiState.value.replyToStatus)
        vm.onTextChanged("ordinary post")
        configuration.complete(Result.success(ComposerConfiguration()))
        advanceUntilIdle()
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals("ordinary post", vm.uiState.value.text)
    }

    @Test fun deletingMentionClearsReplyInSavedDraftAndOutgoingPostButKeepsOtherContent() = runTest(dispatcher) {
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = parent
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences)
        advanceUntilIdle()
        vm.restoreDraft(ComposeDraft("reply", testAccount.sessionId, replyToId = "parent",
            text = "@alice@example.org hello", spoilerText = "CW", attachmentUris = listOf("file:///photo.jpg")))
        advanceUntilIdle()
        vm.onTextChanged("hello @bob@example.org")
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals(listOf("file:///photo.jpg"), vm.uiState.value.attachments.map { it.uri })
        assertEquals("CW", vm.uiState.value.spoilerText)
        vm.saveDraft()
        advanceUntilIdle()
        val draft = preferences.drafts.first().single()
        assertNull(draft.replyToId)
        val restored = model(repository, preferences, replyId = null)
        advanceUntilIdle()
        restored.restoreDraft(draft)
        advanceUntilIdle()
        assertNull(restored.uiState.value.replyToId)
        restored.removeAttachment("file:///photo.jpg")
        restored.post()
        advanceUntilIdle()
        assertNull(posts.single().replyToId)
        assertEquals("hello @bob@example.org", posts.single().text)
    }

    @Test fun punctuationKeepsReplyButDifferentAccountDetachesAndReaddingDoesNotRestore() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = parent
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        vm.onTextChanged("@alice@example.org、ありがとう")
        assertEquals("parent", vm.uiState.value.replyToId)
        vm.onTextChanged("@alice@example.org.other hello")
        assertNull(vm.uiState.value.replyToId)
        vm.onTextChanged("@alice@example.org hello")
        assertNull(vm.uiState.value.replyToId)
    }

    @Test fun mentionDeletedWhileDraftTargetLoadsCannotRemainAReply() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = target.await()
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> =
                error("Do not send while the reply target is unknown")
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()), replyId = null)
        runCurrent()
        vm.restoreDraft(ComposeDraft("reply", testAccount.sessionId, replyToId = "parent", text = "@alice@example.org hello"))
        runCurrent()
        vm.onTextChanged("hello")
        vm.post()
        runCurrent()
        target.complete(Result.success(parent))
        advanceUntilIdle()
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals("hello", vm.uiState.value.text)
    }

    @Test fun lateTargetCannotReplaceRestoredOrdinaryDraft() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                withContext(NonCancellable) { target.await() }
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> = error("No conversation fetch")
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        runCurrent()
        vm.restoreDraft(ComposeDraft("ordinary", testAccount.sessionId, text = "ordinary text"))
        runCurrent()
        target.complete(Result.success(parent))
        advanceUntilIdle()
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals("ordinary text", vm.uiState.value.text)
    }
}
