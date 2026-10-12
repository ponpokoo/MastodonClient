package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.AccountPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
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
    private val groupParent = parent.copy(mentions = listOf(
        StatusMention(testAccount.accountId, "me", ""),
        StatusMention("alice", "alice@example.org", ""),
        StatusMention("bob", "bob@other.example", ""),
        StatusMention("bob", "bob@other.example", ""),
    ))
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
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult())
        }, logMediaFailure = {}),
    )

    @Test fun directReplyOverridesLocalAndServerDefaultsAndSendsAllOtherParticipants() = runTest(dispatcher) {
        for (default in PostVisibility.entries) {
            val posts = mutableListOf<CreateStatusRequest>()
            val repository = object : ScreenRepositoryFake() {
                override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to ServerAccountSettings(
                    testAccount.instanceUrl, testAccount.accountId, defaults = PostingDefaults(default.apiValue),
                )))
                override fun getCachedStatus(session: AccountSession, statusId: String) = groupParent.copy(visibility = "direct")
                override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                    posts += request
                    return Result.success(testStatus())
                }
            }
            val preferences = UserPreferencesStore(MemoryPreferences())
            preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = default))
            val vm = model(repository, preferences)
            advanceUntilIdle()
            assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
            assertEquals("@alice@example.org @bob@other.example ", vm.uiState.value.text)
            vm.onTextChanged(vm.uiState.value.text + "hello")
            vm.post()
            advanceUntilIdle()
            assertEquals("direct", posts.single().visibility)
            assertEquals("parent", posts.single().replyToId)
            assertEquals("@alice@example.org @bob@other.example hello", posts.single().text)
        }
    }

    @Test fun directReplyUsesDirectWhenOnlyLocalDefaultsAreAvailable() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = parent.copy(visibility = "direct")
        }
        for (default in PostVisibility.entries) {
            val preferences = UserPreferencesStore(MemoryPreferences())
            preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(defaultVisibility = default))
            val vm = model(repository, preferences)
            advanceUntilIdle()
            assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
        }
    }

    @Test fun otherReplyVisibilitiesKeepExistingDefaultsAndIncludeParticipants() = runTest(dispatcher) {
        for (visibility in listOf("public", "unlisted", "private")) {
            val repository = object : ScreenRepositoryFake() {
                override val serverSettings = MutableStateFlow(mapOf(testAccount.sessionId to ServerAccountSettings(
                    testAccount.instanceUrl, testAccount.accountId, defaults = PostingDefaults("unlisted"),
                )))
                override fun getCachedStatus(session: AccountSession, statusId: String) = groupParent.copy(visibility = visibility)
            }
            val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
            advanceUntilIdle()
            assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
            assertEquals("@alice@example.org @bob@other.example ", vm.uiState.value.text)
        }
    }

    @Test fun replyingToOwnPostExcludesSelfButKeepsOtherParticipants() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = groupParent.copy(
                author = testStatus().author, visibility = "direct",
            )
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        assertEquals("@alice@example.org @bob@other.example ", vm.uiState.value.text)
        vm.onTextChanged("@bob@other.example hello")
        assertEquals("parent", vm.uiState.value.replyToId)
    }

    @Test fun fetchedDirectReplyInitializesRecipientsAndStillAllowsManualVisibility() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val posts = mutableListOf<CreateStatusRequest>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = target.await()
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        runCurrent()
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        target.complete(Result.success(groupParent.copy(visibility = "direct")))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
        assertEquals("@alice@example.org @bob@other.example ", vm.uiState.value.text)
        vm.setVisibility(PostVisibility.Unlisted)
        vm.post()
        advanceUntilIdle()
        assertEquals("unlisted", posts.single().visibility)
        assertEquals("parent", posts.single().replyToId)
    }

    @Test fun lateDirectTargetDoesNotOverwriteExplicitVisibilityOrEditedText() = runTest(dispatcher) {
        for (editText in listOf(false, true)) {
            val target = CompletableDeferred<Result<TimelineStatus>>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = target.await()
            }
            val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
            runCurrent()
            // Selecting the same value as the default is still an explicit choice.
            vm.setVisibility(PostVisibility.Public)
            if (editText) vm.onTextChanged("@alice@example.org my text")
            target.complete(Result.success(groupParent.copy(visibility = "direct")))
            advanceUntilIdle()
            assertEquals(PostVisibility.Public, vm.uiState.value.visibility)
            assertEquals(if (editText) "@alice@example.org my text" else "@alice@example.org @bob@other.example ", vm.uiState.value.text)
            assertEquals("parent", vm.uiState.value.replyToId)
        }
    }

    @Test fun restoredReplyDraftKeepsSavedVisibilityAndRecipientsEvenWithEmptyText() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String) = groupParent.copy(visibility = "direct")
        }
        for (text in listOf("", "@alice@example.org saved")) {
            val vm = model(repository, UserPreferencesStore(MemoryPreferences()), replyId = null)
            advanceUntilIdle()
            vm.restoreDraft(ComposeDraft("saved", testAccount.sessionId, replyToId = "parent",
                text = text, visibility = PostVisibility.FollowersOnly))
            advanceUntilIdle()
            assertEquals(text, vm.uiState.value.text)
            assertEquals(PostVisibility.FollowersOnly, vm.uiState.value.visibility)
            assertTrue(vm.uiState.value.isVisibilityInitialized)
            assertEquals("parent", vm.uiState.value.replyToId)
        }
    }

    @Test fun replyBufferKeepsStoredVisibilityAndRecipientsEvenWithEmptyText() = runTest(dispatcher) {
        for (text in listOf("", "@alice@example.org buffered")) {
            val target = CompletableDeferred<Result<TimelineStatus>>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = target.await()
            }
            val preferences = UserPreferencesStore(MemoryPreferences())
            preferences.retainComposeBuffer(ComposeDraft("${testAccount.sessionId}:parent:", testAccount.sessionId,
                replyToId = "parent", text = text, visibility = PostVisibility.Unlisted))
            val vm = model(repository, preferences)
            runCurrent()
            assertTrue(vm.uiState.value.isVisibilityInitialized)
            assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
            assertNull(vm.uiState.value.replyToStatus)
            target.complete(Result.success(groupParent.copy(visibility = "direct")))
            advanceUntilIdle()
            assertEquals(text, vm.uiState.value.text)
            assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
            assertTrue(vm.uiState.value.isVisibilityInitialized)
            assertEquals("parent", vm.uiState.value.replyToId)
        }
    }

    @Test fun switchingAccountKeepsMentionTextButPostsWithoutReplyTarget() = runTest(dispatcher) {
        val posts = mutableListOf<Pair<AccountSession, CreateStatusRequest>>()
        val repository = object : ScreenRepositoryFake() {
            override fun getCachedStatus(session: AccountSession, statusId: String): TimelineStatus {
                assertEquals(testAccount, session)
                return groupParent.copy(visibility = "direct")
            }
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += session to request
                return Result.success(testStatus())
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        val text = vm.uiState.value.text + "hello"
        assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
        vm.onTextChanged(text)
        vm.switchPostingAccount(testAccount.sessionId)
        assertEquals("parent", vm.uiState.value.replyToId)
        vm.switchPostingAccount(secondAccount.sessionId)
        advanceUntilIdle()
        assertEquals(secondAccount, vm.uiState.value.selectedSession)
        assertEquals(text, vm.uiState.value.text)
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals(PostVisibility.Public, vm.uiState.value.visibility)
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
        assertTrue(vm.uiState.value.isVisibilityInitialized)
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
            override fun getCachedStatus(session: AccountSession, statusId: String) = groupParent.copy(visibility = "direct")
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts += request
                return Result.success(testStatus())
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences)
        advanceUntilIdle()
        vm.onTextChanged("@alice@example.org hello")
        assertEquals("parent", vm.uiState.value.replyToId)
        vm.restoreDraft(ComposeDraft("reply", testAccount.sessionId, replyToId = "parent",
            text = "@alice@example.org @bob@other.example hello", visibility = PostVisibility.Direct,
            spoilerText = "CW", attachmentUris = listOf("file:///photo.jpg")))
        advanceUntilIdle()
        vm.onTextChanged("hello @bob@example.org")
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals(listOf("file:///photo.jpg"), vm.uiState.value.attachments.map { it.uri })
        assertEquals("CW", vm.uiState.value.spoilerText)
        assertEquals(PostVisibility.Direct, vm.uiState.value.visibility)
        vm.saveDraft()
        advanceUntilIdle()
        val draft = preferences.drafts.first().single()
        assertNull(draft.replyToId)
        assertEquals(PostVisibility.Direct, draft.visibility)
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
        assertEquals("direct", posts.single().visibility)
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
        assertTrue(vm.uiState.value.isVisibilityInitialized)
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
        vm.restoreDraft(ComposeDraft("ordinary", testAccount.sessionId, text = "ordinary text", visibility = PostVisibility.Unlisted))
        runCurrent()
        target.complete(Result.success(groupParent.copy(visibility = "direct")))
        advanceUntilIdle()
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals("ordinary text", vm.uiState.value.text)
        assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
    }

    @Test fun explicitlyClearingReplyDuringFetchKeepsMentionAndIgnoresLateResult() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                withContext(NonCancellable) { target.await() }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        runCurrent()
        vm.onTextChanged("@alice@example.org hello")
        vm.clearReply()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        target.complete(Result.success(groupParent.copy(visibility = "direct")))
        advanceUntilIdle()
        assertEquals("@alice@example.org hello", vm.uiState.value.text)
        assertNull(vm.uiState.value.replyToId)
        assertNull(vm.uiState.value.replyToStatus)
        assertEquals(PostVisibility.Public, vm.uiState.value.visibility)
    }

    @Test fun failedReplyFetchReleasesVisibilityControlAndKeepsError() = runTest(dispatcher) {
        val target = CompletableDeferred<Result<TimelineStatus>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) = target.await()
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        runCurrent()
        assertFalse(vm.uiState.value.isVisibilityInitialized)
        target.complete(Result.failure(IllegalStateException("offline")))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isVisibilityInitialized)
        assertEquals("offline", vm.uiState.value.errorMessage)
        assertEquals("parent", vm.uiState.value.replyToId)
        vm.setVisibility(PostVisibility.Unlisted)
        assertEquals(PostVisibility.Unlisted, vm.uiState.value.visibility)
    }
}
