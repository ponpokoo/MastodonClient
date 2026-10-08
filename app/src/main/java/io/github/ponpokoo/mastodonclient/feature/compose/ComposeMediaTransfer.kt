package io.github.ponpokoo.mastodonclient.feature.compose

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transfers only; attachment values remain owned by the composer. */
internal class ComposeMediaTransfer(
    private val timelineRepository: TimelineRepository,
    private val scope: CoroutineScope,
    private val selectedSession: () -> AccountSession?,
    private val attachment: (String) -> DraftAttachment?,
    private val validationError: (String) -> String?,
    private val updateAttachment: (String, (DraftAttachment) -> DraftAttachment) -> Unit,
    private val posting: Flow<Boolean>,
    private val logMediaFailure: (String) -> Unit,
) {
    private val mediaJobs = mutableMapOf<String, Job>()
    private val mediaMutex = Mutex()
    private val requests = mutableMapOf<String, Long>()
    private var nextRequest = 0L
    var generation = 0
        private set

    fun stop() {
        generation++
        mediaJobs.values.forEach { it.cancel() }
        mediaJobs.clear()
        requests.clear()
    }

    fun cancel(uri: String) {
        requests.remove(uri)
        mediaJobs.remove(uri)?.cancel()
    }

    suspend fun await(uris: List<String>) { uris.mapNotNull { mediaJobs[it] }.forEach { it.join() } }

    fun schedule(uri: String, retry: Boolean = false) {
        validationError(uri)?.let { error ->
            updateAttachment(uri) { it.copy(transferState = MediaTransferState.Failed,
                errorMessage = error, errorDetail = null, validationError = true) }
            return
        }
        if (mediaJobs[uri]?.isActive == true) return
        val session = selectedSession() ?: return
        val initial = attachment(uri) ?: return
        if (!retry && initial.transferState != MediaTransferState.Waiting) return
        val generation = this@ComposeMediaTransfer.generation
        val request = ++nextRequest
        requests[uri] = request
        fun update(transform: (DraftAttachment) -> DraftAttachment) {
            if (generation == this@ComposeMediaTransfer.generation && requests[uri] == request && selectedSession()?.sessionId == session.sessionId) {
                updateAttachment(uri, transform)
            }
        }
        update { it.copy(transferState = MediaTransferState.Waiting, errorMessage = null, errorDetail = null, validationError = false) }
        mediaJobs[uri] = scope.launch {
            try {
                if (!retry) withTimeoutOrNull(1_000) { posting.first { it } }
                mediaMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (generation != this@ComposeMediaTransfer.generation || selectedSession()?.sessionId != session.sessionId) return@withLock
                    validationError(uri)?.let { error ->
                        update { it.copy(transferState = MediaTransferState.Failed,
                            errorMessage = error, errorDetail = null, validationError = true) }
                        return@withLock
                    }
                    var attachment = attachment(uri) ?: return@withLock
                    var processingStarted = System.nanoTime()
                    suspend fun check(id: String) = timelineRepository.checkMedia(session, id).getOrElse { error ->
                        if (error.requestFailure == RequestFailure.Unprocessable) {
                            update { it.copy(mediaId = null, uploadedDescription = null) }
                        }
                        throw error
                    }
                    var media = attachment.mediaId?.let { id ->
                        update { it.copy(transferState = MediaTransferState.Processing) }
                        try { check(id) } catch (error: Exception) {
                            if (error.requestFailure == RequestFailure.NotFound) {
                                update { it.copy(mediaId = null, uploadedDescription = null) }
                                null
                            } else throw error
                        }
                    }
                    if (media == null) {
                        val path = java.net.URI(attachment.uri).path ?: error("Missing file")
                        update { it.copy(transferState = MediaTransferState.Uploading, progress = 0f) }
                        media = timelineRepository.uploadMedia(session, MediaUpload(
                            attachment.fileName, attachment.mimeType, path, attachment.description,
                            onProgress = { progress -> update { it.copy(progress = progress) } },
                        )).getOrThrow()
                        currentCoroutineContext().ensureActive()
                        update { it.copy(mediaId = media.id, uploadedDescription = media.description.orEmpty(), serverType = media.type) }
                        processingStarted = System.nanoTime()
                    }
                    val id = media.id
                    if (!media.ready) {
                        update { it.copy(transferState = MediaTransferState.Processing, progress = null) }
                        val remaining = (60_000 - (System.nanoTime() - processingStarted) / 1_000_000).coerceAtLeast(0)
                        val ready = withTimeoutOrNull(remaining) {
                            while (true) {
                                delay(1_000)
                                val checked = check(id)
                                if (checked.ready) {
                                    update { it.copy(uploadedDescription = checked.description.orEmpty(), serverType = checked.type) }
                                    break
                                }
                            }
                            true
                        } ?: false
                        if (!ready) {
                            update { it.copy(transferState = MediaTransferState.CheckAgain,
                                errorMessage = "サーバーでの処理に時間がかかっています") }
                            return@withLock
                        }
                    } else update { it.copy(uploadedDescription = media.description.orEmpty(), serverType = media.type) }
                    validationError(uri)?.let { error ->
                        update { it.copy(transferState = MediaTransferState.Failed,
                            errorMessage = error, errorDetail = null, validationError = true) }
                        return@withLock
                    }
                    while (true) {
                        attachment = attachment(uri) ?: return@withLock
                        if (attachment.description == attachment.uploadedDescription) break
                        update { it.copy(transferState = MediaTransferState.UpdatingAlt) }
                        timelineRepository.updateMediaDescription(session, id, attachment.description).getOrThrow()
                        val sentDescription = attachment.description
                        update { it.copy(uploadedDescription = sentDescription) }
                    }
                    update { it.copy(transferState = MediaTransferState.Ready, progress = null) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = (error as? RequestException)?.diagnostic ?: error.javaClass.simpleName
                logMediaFailure(detail)
                val message = when {
                    error.requestFailure == RequestFailure.Timeout -> "メディアの通信がタイムアウトしました"
                    error.requiresAuthentication -> "メディア操作の権限を確認してください"
                    error.requestFailure == RequestFailure.Unprocessable -> "サーバーでメディアを処理できませんでした"
                    error.requestFailure == RequestFailure.Server -> "サーバーでエラーが発生しました"
                    error.requestFailure == RequestFailure.Connection -> "通信できませんでした。接続を確認してください"
                    else -> "メディア操作に失敗しました"
                }
                update { it.copy(transferState = MediaTransferState.Failed, errorMessage = message, errorDetail = detail) }
            }
        }
    }

}
