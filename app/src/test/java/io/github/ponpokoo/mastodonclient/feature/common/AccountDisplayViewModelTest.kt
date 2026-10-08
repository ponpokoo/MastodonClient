package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDisplayViewModelTest : ScreenViewModelTestBase() {
    @Test fun accountRemovalRejectsLateEditablePostLoading() = runTest(dispatcher) {
        val auth = Auth()
        val editGate = CompletableDeferred<EditableStatus>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getEditableStatus(session: AccountSession, statusId: String) = Result.success(editGate.await())
        }
        val composer = own(ComposePostViewModel(null, "edited-post", repository, auth,
            UserPreferencesStore(MemoryAuthPreferences()), {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
            }))
        runCurrent()
        auth.logout(); runCurrent()
        editGate.complete(EditableStatus("edited-post", "private server text", "private warning", true)); runCurrent()
        assertNull(composer.uiState.value.selectedSession)
        assertEquals("", composer.uiState.value.text)
        assertEquals("", composer.uiState.value.spoilerText)
    }

    @Test fun removedPostingAccountRejectsLatePostResultEvenWhenRequestIgnoresCancellation() = runTest(dispatcher) {
        val auth = Auth()
        val postGate = CompletableDeferred<TimelineStatus>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String) =
                Result.success(withContext(NonCancellable) { postGate.await() })
        }
        val composer = own(ComposePostViewModel(null, null, repository, auth,
            UserPreferencesStore(MemoryAuthPreferences()), {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
            }))
        runCurrent()
        composer.onTextChanged("private input")
        composer.postIgnoringMissingAlt(); runCurrent()
        assertTrue(composer.uiState.value.isPosting)
        auth.logout(); runCurrent()
        postGate.complete(testStatus()); runCurrent()
        assertNull(composer.uiState.value.selectedSession)
        assertEquals("", composer.uiState.value.text)
        assertFalse(composer.uiState.value.posted)
    }

    @Test fun removingPostingAccountClearsInputAndStopsAttachmentUpload() = runTest(dispatcher) {
        val auth = Auth()
        val uploadGate = CompletableDeferred<UploadedMedia>()
        var cancelled = false
        var posts = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts++
                return Result.success(testStatus())
            }
            override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> {
                try { return Result.success(uploadGate.await()) }
                finally { cancelled = true }
            }
        }
        val preferences = UserPreferencesStore(MemoryAuthPreferences())
        val composer = own(ComposePostViewModel(null, null, repository, auth, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult(
                uris.map { DraftAttachment(it, "photo.jpg", "image/jpeg") }))
        }))
        runCurrent()
        composer.onTextChanged("private input")
        composer.importMedia(listOf("file:///photo.jpg"))
        advanceTimeBy(1_001); runCurrent()
        composer.postIgnoringMissingAlt(); runCurrent()
        auth.logout(); runCurrent()
        assertNull(composer.uiState.value.selectedSession)
        assertEquals("", composer.uiState.value.text)
        assertTrue(composer.uiState.value.attachments.isEmpty())
        assertTrue(cancelled)
        assertEquals(0, posts)
        composer.saveDraft(); runCurrent()
        assertTrue(preferences.drafts.first().isEmpty())
    }
    private class Auth : AuthRepository {
        val accounts = MutableStateFlow(listOf(testAccount, secondAccount))
        var activeId = testAccount.sessionId
        val refreshes = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        override fun observeSessions() = accounts
        override suspend fun restoreSession() = accounts.value.firstOrNull { it.sessionId == activeId }
        override suspend fun getSessions() = accounts.value
        override suspend fun switchSession(sessionId: String): AccountSession? { activeId = sessionId; return restoreSession() }
        override suspend fun refreshAccountDisplay(session: AccountSession): Result<Unit> {
            refreshes += session.sessionId
            gate.await()
            return Result.failure(java.io.IOException("offline"))
        }
        override suspend fun logout() { accounts.value = accounts.value.filterNot { it.sessionId == activeId } }
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        fun update(account: AccountSession) { accounts.value = accounts.value.map { if (it.sessionId == account.sessionId) account else it } }
    }

    @Test fun startupAndSwitchShowSavedDataBeforeRefreshAndIgnoreRefreshFailure() = runTest(dispatcher) {
        val auth = Auth()
        val main = own(MainSessionViewModel(auth, ScreenRepositoryFake()))
        runCurrent()
        assertEquals(testAccount, main.uiState.value.session)
        assertEquals(listOf(testAccount.sessionId), auth.refreshes)
        main.switchAccount(secondAccount.sessionId)
        runCurrent()
        assertEquals(secondAccount, main.uiState.value.session)
        assertEquals(listOf(testAccount.sessionId, secondAccount.sessionId), auth.refreshes)
        auth.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(secondAccount, main.uiState.value.session)
        assertNull(main.uiState.value.errorMessage)
        assertFalse(main.uiState.value.requiresLogin)
    }

    @Test fun metadataKeepsBrowsingGenerationStreamAndInFlightTimelineRequest() = runTest(dispatcher) {
        val auth = Auth()
        val page = CompletableDeferred<TimelinePage>()
        var connections = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(page.await())
            override fun observeUserStream(session: AccountSession) = flow<TimelineStreamEvent> { connections++; awaitCancellation() }
        }
        val main = own(MainSessionViewModel(auth, repository))
        val timeline = own(TimelineViewModel(repository, main.browsing))
        runCurrent()
        val snapshot = main.browsing.snapshot.value
        val updated = testAccount.copy(displayName = "Fresh", username = "fresh", avatarUrl = "new.png", avatarRevision = 1)
        auth.update(updated)
        runCurrent()
        assertEquals(updated, main.uiState.value.session)
        assertEquals(snapshot, main.browsing.snapshot.value)
        assertEquals(1, connections)
        page.complete(TimelinePage(listOf(testStatus("pending")), null, true))
        runCurrent()
        assertEquals("pending", timeline.uiState.value.statuses.single().statusId)
        assertEquals(1, connections)
    }

    @Test fun composerMetadataKeepsPostingAccountInputAttachmentDraftAndMediaOperation() = runTest(dispatcher) {
        val auth = Auth()
        val uploadResponse = CompletableDeferred<UploadedMedia>()
        var uploads = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> { uploads++; return Result.success(uploadResponse.await()) }
        }
        val preferences = UserPreferencesStore(MemoryAuthPreferences())
        val composer = own(ComposePostViewModel(null, null, repository, auth, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult(uris.map { DraftAttachment(it, "photo.jpg", "image/jpeg") }))
        }))
        runCurrent()
        composer.onTextChanged("saved draft")
        composer.saveDraft()
        runCurrent()
        composer.switchPostingAccount(secondAccount.sessionId)
        runCurrent()
        composer.onTextChanged("keep this text")
        composer.importMedia(listOf("file:///photo.jpg"))
        advanceTimeBy(1_001)
        runCurrent()
        val before = composer.uiState.value
        val updated = secondAccount.copy(displayName = "Fresh", avatarRevision = 1)
        auth.update(updated)
        runCurrent()
        val after = composer.uiState.value
        assertEquals(updated, after.selectedSession)
        assertEquals(before.copy(sessions = auth.accounts.value, selectedSession = updated), after)
        assertEquals("saved draft", preferences.drafts.first().single().text)
        assertEquals(1, uploads)
        uploadResponse.complete(UploadedMedia("uploaded", "image", null, "", true))
        advanceUntilIdle()
        assertEquals("uploaded", composer.uiState.value.attachments.single().mediaId)
        assertEquals("keep this text", composer.uiState.value.text)
        assertEquals(secondAccount.sessionId, composer.uiState.value.selectedSession?.sessionId)
        assertEquals(testAccount.sessionId, auth.activeId)
    }

    @Test fun bothOwnProfileScreensObserveMetadataWithoutDiscardingPosts() = runTest(dispatcher) {
        val auth = Auth()
        val repository = ScreenRepositoryFake()
        val main = own(MainSessionViewModel(auth, repository))
        val ownProfile = own(OwnProfileViewModel(repository, main.browsing, auth))
        val accountProfile = own(AccountProfileViewModel(testAccount.accountId, repository, auth))
        runCurrent()
        ownProfile.loadProfile()
        runCurrent()
        val beforeOwn = ownProfile.uiState.value
        val beforeAccount = accountProfile.uiState.value
        auth.update(testAccount.copy(displayName = "Fresh", username = "fresh", avatarRevision = 2))
        runCurrent()
        assertEquals("Fresh", ownProfile.uiState.value.profile?.author?.displayName)
        assertEquals("Fresh", accountProfile.uiState.value.profile?.author?.displayName)
        assertEquals(beforeOwn.profileTabs, ownProfile.uiState.value.profileTabs)
        assertEquals(beforeAccount.profileTabs, accountProfile.uiState.value.profileTabs)
    }

    @Test fun pendingRelationshipAcceptsDisplayChangesButRejectsCredentialChanges() = runTest(dispatcher) {
        for (credentialsChanged in listOf(false, true)) {
            val auth = Auth()
            val relation = CompletableDeferred<AccountRelationship>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getRelationship(session: AccountSession, accountId: String) = Result.success(relation.await())
            }
            val profile = own(AccountProfileViewModel(testAccount.accountId, repository, auth))
            runCurrent()
            profile.loadModerationMenu("other-user")
            runCurrent()
            auth.update(if (credentialsChanged) testAccount.copy(accessToken = "replacement-token")
                else testAccount.copy(displayName = "Fresh", avatarRevision = 1))
            runCurrent()
            relation.complete(AccountRelationship(following = true))
            runCurrent()
            assertEquals(!credentialsChanged, profile.moderationMenuState.value.relationship?.following == true)
        }
    }
}
