package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeMediaFlowTest : ScreenViewModelTestBase() {
    private class MemoryPreferences : DataStore<Preferences> {
        var failWrites = false
        var writeGate: CompletableDeferred<Unit>? = null
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeGate?.await()
            if (failWrites) throw java.io.IOException("disk full")
            return transform(data.value).also { data.value = it }
        }
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
            val type = when {
                upload.mimeType.startsWith("audio/") -> "audio"
                upload.mimeType.startsWith("video/") -> "video"
                else -> "image"
            }
            return Result.success(UploadedMedia("id-${upload.fileName}", type, null, upload.description, ready))
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

    private fun model(
        repository: Repository,
        preferences: UserPreferencesStore = UserPreferencesStore(MemoryPreferences()),
        sharedUris: List<String> = emptyList(),
        deleted: MutableList<String> = mutableListOf(),
        importer: suspend (List<String>) -> MediaImportResult = { uris -> MediaImportResult(uris.map {
            DraftAttachment(it, it.substringAfterLast('/'), "image/jpeg")
        }) },
    ) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun logout() = Unit
        }, preferences, { deleted += it }, object : DraftMediaRepository {
            override suspend fun importMedia(uris: List<String>) = Result.success(importer(uris))
        }, initialSharedMediaUris = sharedUris, logMediaFailure = {}),
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

    @Test fun savingClearsComposerKeepsFilesAndCreatesIndependentDrafts() = runTest(dispatcher) {
        val preferences = UserPreferencesStore(MemoryPreferences())
        val deleted = mutableListOf<String>()
        val vm = model(Repository(), preferences, deleted = deleted)
        advanceUntilIdle()
        val defaultVisibility = vm.uiState.value.visibility
        vm.onTextChanged("first")
        vm.onSpoilerChanged("warning")
        vm.setLanguage("en")
        vm.importMedia(listOf("file:///one.jpg"))
        advanceUntilIdle()
        vm.saveDraft()
        advanceUntilIdle()
        val first = preferences.drafts.first().single()
        assertEquals("first", first.text)
        assertTrue(vm.uiState.value.text.isEmpty())
        assertTrue(vm.uiState.value.spoilerText.isEmpty())
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertNull(vm.uiState.value.language)
        assertEquals(defaultVisibility, vm.uiState.value.visibility)
        assertTrue(deleted.isEmpty())
        vm.retainInputThen {}
        val reopened = model(Repository(), preferences)
        advanceUntilIdle()
        assertTrue(reopened.uiState.value.text.isEmpty())
        vm.onTextChanged("second")
        vm.enablePoll()
        vm.setPollOption(0, "yes")
        vm.setPollOption(1, "no")
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(2, preferences.drafts.first().size)
        assertTrue(vm.uiState.value.pollOptions.isEmpty())
        vm.restoreDraft(first)
        advanceUntilIdle()
        vm.onTextChanged("edited first")
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(2, preferences.drafts.first().size)
        assertEquals("edited first", preferences.drafts.first().single { it.key == first.key }.text)
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(2, preferences.drafts.first().size)
    }

    @Test fun failedSaveRetainsInputAndSavingBlocksAccountSwitchAndDoubleSave() = runTest(dispatcher) {
        val store = MemoryPreferences()
        val preferences = UserPreferencesStore(store)
        val vm = model(Repository(), preferences)
        advanceUntilIdle()
        vm.onTextChanged("keep me")
        vm.importMedia(listOf("file:///one.jpg"))
        advanceUntilIdle()
        store.failWrites = true
        store.writeGate = CompletableDeferred()
        vm.saveDraft()
        runCurrent()
        vm.switchPostingAccount(secondAccount.sessionId)
        vm.onTextChanged("lost edit")
        vm.removeAttachment("file:///one.jpg")
        vm.saveDraft()
        assertTrue(vm.uiState.value.isSavingDraft)
        assertEquals(testAccount.sessionId, vm.uiState.value.selectedSession?.sessionId)
        store.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSavingDraft)
        assertEquals("keep me", vm.uiState.value.text)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(preferences.drafts.first().isEmpty())
        store.failWrites = false
        vm.saveDraft()
        advanceUntilIdle()
        assertEquals(1, preferences.drafts.first().size)
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test fun savingDuringUploadIgnoresLateCompletionAndKeepsTheSavedFile() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = object : Repository() {
            override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> {
                withContext(NonCancellable) { gate.await() }
                return super.uploadMedia(session, upload)
            }
        }
        val preferences = UserPreferencesStore(MemoryPreferences())
        val deleted = mutableListOf<String>()
        val vm = model(repository, preferences, deleted = deleted)
        runCurrent()
        vm.importMedia(listOf("file:///one.jpg"))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        vm.saveDraft()
        runCurrent()
        vm.onTextChanged("next post")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("next post", vm.uiState.value.text)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertEquals(listOf("file:///one.jpg"), preferences.drafts.first().single().attachmentUris)
        assertTrue(deleted.isEmpty())
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
            attachmentMimeTypes = mapOf("file:///one.jpg" to "image/jpeg"),
            attachmentMediaIds = mapOf("file:///one.jpg" to "expired"),
            attachmentDescriptions = mapOf("file:///one.jpg" to "keep ALT"),
        ))
        advanceUntilIdle()
        assertEquals(1, repository.uploads.size)
        assertEquals("keep ALT", repository.uploads.single().description)
        assertEquals("id-one.jpg", vm.uiState.value.attachments.single().mediaId)
    }

    @Test fun partialRejectionDoesNotConsumeTheServerLimitOrUploadRejectedFiles() = runTest(dispatcher) {
        val repository = object : Repository() {
            override suspend fun getComposerConfiguration(session: AccountSession) =
                Result.success(ComposerConfiguration(maxMediaAttachments = 1, supportedMimeTypes = setOf("image/jpeg")))
        }
        val deleted = mutableListOf<String>()
        val vm = model(repository, deleted = deleted, importer = { MediaImportResult(listOf(
            DraftAttachment("file:///unsupported.pdf", "unsupported.pdf", "application/pdf"),
            DraftAttachment("file:///one.jpg", "one.jpg", "image/jpeg"),
            DraftAttachment("file:///two.jpg", "two.jpg", "image/jpeg"),
        ), listOf(RejectedMedia("broken.jpg", "image/jpeg", MediaRejectionReason.Unreadable))) })
        runCurrent()
        vm.importMedia(listOf("content://files/batch")); advanceUntilIdle()
        assertEquals(listOf("one.jpg"), repository.uploads.map { it.fileName })
        assertEquals(setOf("file:///unsupported.pdf", "file:///two.jpg"), deleted.toSet())
        assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("application/pdf"))
        assertTrue(vm.uiState.value.errorMessage.orEmpty().contains("4件中1件"))
    }

    @Test fun sharedAudioIsCopiedBeforeConfigurationWaitAndNeverUploadedBeforeValidation() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var copied = false
        val repository = object : Repository() {
            override suspend fun getComposerConfiguration(session: AccountSession): Result<ComposerConfiguration> {
                assertTrue(copied)
                gate.await()
                return super.getComposerConfiguration(session)
            }
        }
        val vm = model(repository, sharedUris = listOf("content://files/song"), importer = {
            copied = true
            MediaImportResult(listOf(DraftAttachment("file:///song.mp3", "song.mp3", "audio/mpeg")))
        })
        runCurrent()
        assertTrue(copied)
        assertTrue(repository.uploads.isEmpty())
        assertTrue(vm.uiState.value.attachments.isEmpty())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("audio/mpeg", repository.uploads.single().mimeType)
        assertEquals(MediaTransferState.Ready, vm.uiState.value.attachments.single().transferState)
    }

    @Test fun missingConfigurationRetainsCopiedShareForRetryWithoutReimporting() = runTest(dispatcher) {
        var available = false
        var imports = 0
        val repository = object : Repository() {
            override suspend fun getComposerConfiguration(session: AccountSession) =
                if (available) super.getComposerConfiguration(session) else Result.failure(java.io.IOException())
        }
        val vm = model(repository, sharedUris = listOf("content://files/song"), importer = {
            imports++
            MediaImportResult(listOf(DraftAttachment("file:///song.mp3", "song.mp3", "audio/mpeg")))
        })
        advanceUntilIdle()
        assertTrue(repository.uploads.isEmpty())
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertNotNull(vm.uiState.value.errorMessage)
        assertEquals(1, vm.uiState.value.pendingSharedMediaCount)
        available = true
        vm.retryMediaConfiguration(); advanceUntilIdle()
        assertEquals(1, imports)
        assertEquals("song.mp3", repository.uploads.single().fileName)
        assertEquals(0, vm.uiState.value.pendingSharedMediaCount)
    }

    @Test fun sharedImagesUseTheServerLimitAndUnsupportedDraftsNeverReupload() = runTest(dispatcher) {
        val repository = object : Repository() {
            override suspend fun getComposerConfiguration(session: AccountSession) =
                Result.success(ComposerConfiguration(maxMediaAttachments = 6, supportedMimeTypes = setOf("image/jpeg")))
        }
        val vm = model(repository, sharedUris = (1..6).map { "file:///$it.jpg" })
        advanceUntilIdle()
        assertEquals(6, repository.uploads.size)
        vm.restoreDraft(io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft(
            key = "unsupported", sessionId = testAccount.sessionId,
            attachmentUris = listOf("file:///song.mp3"),
            attachmentMimeTypes = mapOf("file:///song.mp3" to "audio/mpeg"),
        ))
        advanceUntilIdle()
        assertEquals(6, repository.uploads.size)
        assertTrue(vm.uiState.value.attachments.single().validationError)
        vm.post(skipAltReminder = true); advanceUntilIdle()
        assertTrue(repository.posts.isEmpty())
    }

    @Test fun normalPickerAndSharesUseTheSameCombinationRule() = runTest(dispatcher) {
        val repository = Repository()
        val vm = model(repository, importer = { MediaImportResult(listOf(
            DraftAttachment("file:///one.jpg", "one.jpg", "image/jpeg"),
            DraftAttachment("file:///song.mp3", "song.mp3", "audio/mpeg"),
        )) })
        runCurrent()
        vm.importMedia(listOf("content://files/image", "content://files/audio")); advanceUntilIdle()
        assertEquals(listOf("one.jpg"), repository.uploads.map { it.fileName })
        assertEquals(1, vm.uiState.value.attachments.size)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test fun cancellingImportDiscardsLateFilesAndNeverUploadsThem() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repository = Repository()
        val deleted = mutableListOf<String>()
        val vm = model(repository, deleted = deleted, importer = {
            withContext(NonCancellable) { gate.await() }
            MediaImportResult(listOf(DraftAttachment("file:///late.jpg", "late.jpg", "image/jpeg")))
        })
        runCurrent()
        vm.importMedia(listOf("content://files/late")); runCurrent()
        vm.viewModelScope.cancel()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("file:///late.jpg"), deleted)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(repository.uploads.isEmpty())
    }

    @Test fun cancellingPendingShareAllowsTextPostWithoutCapabilities() = runTest(dispatcher) {
        val repository = object : Repository() {
            override suspend fun getComposerConfiguration(session: AccountSession) =
                Result.success(ComposerConfiguration())
        }
        val deleted = mutableListOf<String>()
        val vm = model(repository, sharedUris = listOf("file:///one.jpg"), deleted = deleted)
        advanceUntilIdle()
        vm.onTextChanged("text only")
        vm.discardPendingSharedMedia()
        vm.post(skipAltReminder = true); advanceUntilIdle()
        assertEquals(listOf("file:///one.jpg"), deleted)
        assertEquals("text only", repository.posts.single().text)
        assertTrue(repository.posts.single().mediaIds.isEmpty())
        assertTrue(repository.uploads.isEmpty())
    }

    @Test fun initialShareRestoresExistingInputBeforeCopyAndRetainsItWhenClosedDuringImport() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val preferences = UserPreferencesStore(MemoryPreferences())
        val key = "${testAccount.sessionId}:new:"
        preferences.retainComposeBuffer(io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft(
            key = key, sessionId = testAccount.sessionId, text = "existing input",
        ))
        val vm = model(Repository(), preferences, sharedUris = listOf("content://files/photo"), importer = {
            gate.await()
            MediaImportResult()
        })
        runCurrent()
        assertTrue(vm.uiState.value.isImportingMedia)
        assertEquals("existing input", vm.uiState.value.text)
        vm.retainInputThen {}
        vm.viewModelScope.cancel(); advanceUntilIdle()
        assertEquals("existing input", preferences.getComposeBuffer(key)?.text)
    }
}
