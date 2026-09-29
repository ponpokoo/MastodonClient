package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeMediaFlowTest : ScreenViewModelTestBase() {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private open class Repository : ScreenRepositoryFake() {
        val uploads = mutableListOf<MediaUpload>()
        val checks = mutableListOf<String>()
        val updates = mutableListOf<Pair<String, String>>()
        val posts = mutableListOf<CreateStatusRequest>()
        var ready = true
        var uploadGate: CompletableDeferred<Unit>? = null
        var altGate: CompletableDeferred<Unit>? = null
        override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> {
            uploads += upload
            uploadGate?.await()
            return Result.success(UploadedMedia("id-${upload.fileName}", "image", null, upload.description, ready))
        }
        override suspend fun checkMedia(session: AccountSession, id: String): Result<UploadedMedia> {
            checks += id
            return Result.success(UploadedMedia(id, "image", null, "", ready))
        }
        override suspend fun updateMediaDescription(session: AccountSession, id: String, description: String): Result<Unit> {
            updates += id to description
            altGate?.await()
            return Result.success(Unit)
        }
        override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
            posts += request
            return Result.success(testStatus())
        }
    }

    private fun model(repository: Repository, preferences: UserPreferencesStore = UserPreferencesStore(MemoryPreferences())) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, {}, object : DraftMediaRepository {
            override suspend fun importMedia(uris: List<String>) = Result.success(uris.map {
                DraftAttachment(it, it.substringAfterLast('/'), "image/jpeg")
            })
        }, logMediaFailure = {}),
    )

    @Test fun deletingDuringDebounceNeverUploads() = runTest(dispatcher) {
        val repository = Repository()
        val vm = model(repository)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg"))
        runCurrent()
        advanceTimeBy(999)
        assertTrue(repository.uploads.isEmpty())
        vm.removeAttachment("file:///one.jpg")
        advanceUntilIdle()
        assertTrue(repository.uploads.isEmpty())
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test fun onlyOneUploadRunsAndQueuedDeletionIsNotSent() = runTest(dispatcher) {
        val repository = Repository().apply { uploadGate = CompletableDeferred() }
        val vm = model(repository)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg", "file:///two.jpg"))
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("one.jpg"), repository.uploads.map { it.fileName })
        vm.removeAttachment("file:///two.jpg")
        repository.uploadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals(MediaTransferState.Ready, vm.uiState.value.attachments.single().transferState)
    }

    @Test fun postWaitsForLatestAlt() = runTest(dispatcher) {
        val repository = Repository().apply { uploadGate = CompletableDeferred(); altGate = CompletableDeferred() }
        val vm = model(repository)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        vm.setAttachmentDescription("file:///one.jpg", "latest ALT")
        vm.post(skipAltReminder = true); runCurrent()
        repository.uploadGate!!.complete(Unit); runCurrent()
        assertEquals(listOf("id-one.jpg" to "latest ALT"), repository.updates)
        assertTrue(repository.posts.isEmpty())
        repository.altGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("id-one.jpg"), repository.posts.single().mediaIds)
        assertTrue(vm.uiState.value.posted)
    }

    @Test fun immediatePostStartsUploadWithoutWaitingOneSecond() = runTest(dispatcher) {
        val repository = Repository()
        val vm = model(repository)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg")); runCurrent()
        vm.post(skipAltReminder = true); runCurrent()
        assertEquals(1, repository.uploads.size)
        assertEquals(1, repository.posts.size)
    }

    @Test fun processingTimeoutKeepsIdAndRecheckDoesNotUploadAgain() = runTest(dispatcher) {
        val repository = Repository().apply { ready = false }
        val vm = model(repository)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg")); advanceUntilIdle()
        val pending = vm.uiState.value.attachments.single()
        assertEquals(MediaTransferState.CheckAgain, pending.transferState)
        assertEquals("id-one.jpg", pending.mediaId)
        vm.post(skipAltReminder = true); runCurrent()
        assertTrue(repository.posts.isEmpty())
        repository.ready = true
        vm.retryMedia(pending.uri); advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals(MediaTransferState.Ready, vm.uiState.value.attachments.single().transferState)
    }

    @Test fun draftRestorationChecksSavedIdAndPreservesLatestAlt() = runTest(dispatcher) {
        val repository = Repository()
        val preferences = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, preferences)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg")); advanceUntilIdle()
        vm.setAttachmentDescription("file:///one.jpg", "saved ALT"); advanceUntilIdle()
        vm.saveDraft(); advanceUntilIdle()
        val draft = preferences.drafts.first().single()
        assertEquals(testAccount.sessionId, draft.sessionId)
        assertEquals("id-one.jpg", draft.attachmentMediaIds["file:///one.jpg"])
        val restored = model(repository, preferences); runCurrent()
        restored.restoreDraft(draft); advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals("id-one.jpg", repository.checks.last())
        assertEquals("saved ALT", restored.uiState.value.attachments.single().description)
        assertEquals(MediaTransferState.Ready, restored.uiState.value.attachments.single().transferState)
    }

    @Test fun uploadFailureRetainsAttachmentAndBlocksPostUntilRetry() = runTest(dispatcher) {
        var fail = true
        val repository = object : Repository() {
            override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> =
                if (fail) Result.failure(java.net.SocketTimeoutException("private diagnostic"))
                else super.uploadMedia(session, upload)
        }
        val vm = model(repository); runCurrent()
        vm.onTextChanged("keep my text")
        vm.importMedia(listOf("file:///one.jpg")); advanceUntilIdle()
        val failed = vm.uiState.value.attachments.single()
        assertEquals(MediaTransferState.Failed, failed.transferState)
        assertEquals("SocketTimeoutException", failed.errorDetail)
        assertFalse(failed.errorMessage.orEmpty().contains("private diagnostic"))
        vm.post(skipAltReminder = true); runCurrent()
        assertTrue(repository.posts.isEmpty())
        assertEquals("keep my text", vm.uiState.value.text)
        fail = false
        vm.retryMedia(failed.uri); advanceUntilIdle()
        assertEquals(MediaTransferState.Ready, vm.uiState.value.attachments.single().transferState)
    }

    @Test fun accountSwitchCancelsUploadAndDoesNotMixAttachments() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = Repository().apply { uploadGate = gate }
        val vm = model(repository); runCurrent()
        vm.importMedia(listOf("file:///one.jpg")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        vm.switchPostingAccount(secondAccount.sessionId); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(secondAccount.sessionId, vm.uiState.value.selectedSession?.sessionId)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        vm.switchPostingAccount(testAccount.sessionId); advanceUntilIdle()
        assertEquals(MediaTransferState.Ready, vm.uiState.value.attachments.single().transferState)
    }

    @Test fun missingDraftMediaIsUploadedAgainFromLocalFile() = runTest(dispatcher) {
        val repository = object : Repository() {
            override suspend fun checkMedia(session: AccountSession, id: String): Result<UploadedMedia> =
                Result.failure(retrofit2.HttpException(retrofit2.Response.error<Unit>(404,
                    okhttp3.ResponseBody.create(null, ""))))
        }
        val vm = model(repository); runCurrent()
        vm.restoreDraft(io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft(
            key = "old", sessionId = testAccount.sessionId,
            attachmentUris = listOf("file:///one.jpg"),
            attachmentMediaIds = mapOf("file:///one.jpg" to "expired"),
            attachmentDescriptions = mapOf("file:///one.jpg" to "keep ALT"),
        ))
        advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals("keep ALT", repository.uploads.single().description)
        assertEquals("id-one.jpg", vm.uiState.value.attachments.single().mediaId)
    }
}
