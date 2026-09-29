package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.ComposerConfiguration
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.MediaUpload
import io.github.ponpokoo.mastodonclient.domain.model.EditableStatus
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import retrofit2.HttpException
import io.github.ponpokoo.mastodonclient.domain.model.MediaTransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ComposePostUiState(
    val sessions: List<AccountSession> = emptyList(),
    val selectedSession: AccountSession? = null,
    val text: String = "",
    val spoilerText: String = "",
    val visibility: PostVisibility = PostVisibility.Public,
    val sensitive: Boolean = false,
    val attachments: List<DraftAttachment> = emptyList(),
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long = 86_400,
    val pollMultiple: Boolean = false,
    val configuration: ComposerConfiguration = ComposerConfiguration(),
    val customEmojis: List<CustomEmoji> = emptyList(),
    val drafts: List<ComposeDraft> = emptyList(),
    val replyToStatus: TimelineStatus? = null,
    val quoteToStatus: TimelineStatus? = null,
    val quoteStatusId: String? = null,
    val quotingNative: Boolean = false,
    val mentionCandidates: List<StatusAuthor> = emptyList(),
    val isLoadingMentions: Boolean = false,
    val preferences: AppPreferences = AppPreferences(),
    val isLoading: Boolean = true,
    val isPosting: Boolean = false,
    val isImportingMedia: Boolean = false,
    val posted: Boolean = false,
    val errorMessage: String? = null,
    val altReminderVisible: Boolean = false,
    val actionMessage: String? = null,
)

class ComposePostViewModel(
    private val initialReplyToId: String?,
    private val editStatusId: String?,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val preferencesStore: UserPreferencesStore,
    private val deleteDraftFile: (String) -> Unit,
    private val draftMediaRepository: DraftMediaRepository,
    private val initialQuoteStatusId: String? = null,
    private val initialQuoteStatusUrl: String? = null,
    private val nativeQuote: Boolean = false,
    private val initialSharedText: String? = null,
    private val initialSharedMediaUri: String? = null,
    private val logMediaFailure: (String) -> Unit = { android.util.Log.w("MediaUpload", it) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(ComposePostUiState())
    val uiState: StateFlow<ComposePostUiState> = _uiState.asStateFlow()
    private var idempotencyKey = UUID.randomUUID().toString()
    private var activeReplyToId: String? = initialReplyToId
    private var activeQuoteStatusId: String? = initialQuoteStatusId
    private var activeQuoteStatusUrl: String? = initialQuoteStatusUrl
    private var activeNativeQuote: Boolean = nativeQuote
    private var initialShareApplied = false
    private val mediaJobs = mutableMapOf<String, Job>()
    private val mediaMutex = Mutex()
    private var mediaGeneration = 0

    private fun stopMedia() {
        mediaGeneration++
        mediaJobs.values.forEach { it.cancel() }
        mediaJobs.clear()
    }

    private fun scheduleAttachments() {
        _uiState.value.attachments.forEach { scheduleMedia(it.uri) }
    }

    fun retryMedia(uri: String) {
        if (_uiState.value.isPosting) return
        scheduleMedia(uri, retry = true)
    }

    private fun scheduleMedia(uri: String, retry: Boolean = false) {
        if (mediaJobs[uri]?.isActive == true) return
        val session = _uiState.value.selectedSession ?: return
        val initial = _uiState.value.attachments.find { it.uri == uri } ?: return
        if (!retry && initial.transferState != MediaTransferState.Waiting) return
        val generation = mediaGeneration
        fun update(transform: (DraftAttachment) -> DraftAttachment) {
            _uiState.update { state ->
                if (generation != mediaGeneration || state.selectedSession?.sessionId != session.sessionId) state
                else state.copy(attachments = state.attachments.map { if (it.uri == uri) transform(it) else it })
            }
        }
        update { it.copy(transferState = MediaTransferState.Waiting, errorMessage = null, errorDetail = null) }
        mediaJobs[uri] = viewModelScope.launch {
            try {
                if (!retry) withTimeoutOrNull(1_000) { uiState.first { it.isPosting } }
                mediaMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    var attachment = _uiState.value.attachments.find { it.uri == uri } ?: return@withLock
                    var processingStarted = System.nanoTime()
                    suspend fun check(id: String) = timelineRepository.checkMedia(session, id).getOrElse { error ->
                        if ((error as? HttpException)?.code() == 422) {
                            update { it.copy(mediaId = null, uploadedDescription = null) }
                        }
                        throw error
                    }
                    var media = attachment.mediaId?.let { id ->
                        update { it.copy(transferState = MediaTransferState.Processing) }
                        try { check(id) } catch (error: Exception) {
                            if ((error as? HttpException)?.code() == 404) {
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
                        update { it.copy(mediaId = media.id, uploadedDescription = media.description.orEmpty()) }
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
                                    update { it.copy(uploadedDescription = checked.description.orEmpty()) }
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
                    } else update { it.copy(uploadedDescription = media.description.orEmpty()) }
                    while (true) {
                        attachment = _uiState.value.attachments.find { it.uri == uri } ?: return@withLock
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
                val code = (error as? HttpException)?.code()
                val detail = code?.let { "HTTP $it" } ?: error.javaClass.simpleName
                logMediaFailure(detail)
                val message = when {
                    error is java.io.InterruptedIOException -> "メディアの通信がタイムアウトしました"
                    code == 401 || code == 403 -> "メディア操作の権限を確認してください"
                    code == 422 -> "サーバーでメディアを処理できませんでした"
                    code != null && code >= 500 -> "サーバーでエラーが発生しました"
                    error is java.io.IOException -> "通信できませんでした。接続を確認してください"
                    else -> "メディア操作に失敗しました"
                }
                update { it.copy(transferState = MediaTransferState.Failed, errorMessage = message, errorDetail = detail) }
            }
        }
    }

    init {
        viewModelScope.launch {
            val sessions = authRepository.getSessions()
            val selected = authRepository.restoreSession() ?: sessions.firstOrNull()
            val preferences = preferencesStore.preferences.first()
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    selectedSession = selected,
                    preferences = preferences,
                    visibility = preferences.forAccount(selected?.sessionId).defaultVisibility,
                    drafts = preferencesStore.drafts.first().filter { draft -> draft.sessionId == selected?.sessionId }
                        .sortedByDescending(ComposeDraft::updatedAtEpochMillis),
                    isLoading = selected != null,
                    quotingNative = activeQuoteStatusId != null && activeNativeQuote,
                    quoteStatusId = activeQuoteStatusId,
                )
            }
            selected?.let { session ->
                loadAccountData(session, restoreBuffer = editStatusId == null)
                applyInitialShare()
                activeQuoteStatusId?.let { quoteId -> loadQuoteTarget(session, quoteId) }
                initialReplyToId?.let { loadReplyTarget(session, it, insertMention = _uiState.value.text.isBlank()) }
                editStatusId?.let { statusId ->
                    timelineRepository.getEditableStatus(session, statusId).fold(
                        onSuccess = { source -> _uiState.update { state -> state.copy(
                            text = source.text,
                            spoilerText = source.spoilerText,
                            sensitive = source.sensitive,
                            isLoading = false,
                        ) } },
                        onFailure = { error -> _uiState.update { it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "編集する投稿を取得できませんでした",
                        ) } },
                    )
                }
            }
        }
    }

    fun onTextChanged(text: String) {
        if (text.length <= _uiState.value.configuration.maxCharacters) change { it.copy(text = text) }
    }

    fun onSpoilerChanged(text: String) = change { it.copy(spoilerText = text) }
    fun setVisibility(value: PostVisibility) = change { it.copy(visibility = value) }
    fun setSensitive(value: Boolean) = change { it.copy(sensitive = value) }

    fun importMedia(uris: List<String>) {
        val state = _uiState.value
        if (state.isImportingMedia || state.isPosting || state.isLoading || state.selectedSession == null) return
        val remaining = (state.configuration.maxMediaAttachments - state.attachments.size).coerceAtLeast(0)
        if (remaining == 0 || uris.isEmpty()) return
        if (state.pollOptions.isNotEmpty()) {
            _uiState.update { it.copy(errorMessage = "メディアと投票は同時に追加できません") }
            return
        }
        _uiState.update { it.copy(isImportingMedia = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                draftMediaRepository.importMedia(uris.take(remaining)).fold(
                    onSuccess = { addAttachments(it) },
                    onFailure = { _uiState.update { it.copy(errorMessage = "添付ファイルを読み込めませんでした") } },
                )
            } finally {
                _uiState.update { it.copy(isImportingMedia = false) }
            }
        }
    }

    private fun applyInitialShare() {
        if (initialShareApplied) return
        initialShareApplied = true
        initialSharedText?.trim()?.takeIf(String::isNotEmpty)?.let { sharedText ->
            _uiState.update { state ->
                val merged = when {
                    sharedText in state.text -> state.text
                    state.text.isBlank() -> sharedText
                    else -> "${state.text.trimEnd()}\n$sharedText"
                }
                state.copy(text = merged.take(state.configuration.maxCharacters))
            }
        }
        initialSharedMediaUri?.let { importMedia(listOf(it)) }
    }

    private fun waitForMediaImport(): Boolean {
        if (!_uiState.value.isImportingMedia) return false
        _uiState.update { it.copy(actionMessage = "添付ファイルの読み込み完了をお待ちください") }
        return true
    }

    private fun addAttachments(items: List<DraftAttachment>) {
        val state = _uiState.value
        val remaining = (state.configuration.maxMediaAttachments - state.attachments.size).coerceAtLeast(0)
        change { it.copy(attachments = (it.attachments + items.take(remaining)).distinctBy(DraftAttachment::uri)) }
        scheduleAttachments()
    }

    fun removeAttachment(uri: String) {
        if (_uiState.value.isPosting) return
        mediaJobs.remove(uri)?.cancel()
        deleteDraftFile(uri)
        change {
            it.copy(attachments = it.attachments.filterNot { attachment -> attachment.uri == uri })
        }
    }

    fun setAttachmentDescription(uri: String, description: String) {
        if (_uiState.value.isPosting) return
        change { state ->
        state.copy(attachments = state.attachments.map {
            if (it.uri == uri) it.copy(description = description.take(state.configuration.mediaDescriptionLimit)) else it
        })
        }
        val attachment = _uiState.value.attachments.find { it.uri == uri }
        if (attachment?.transferState == MediaTransferState.Ready) scheduleMedia(uri, retry = true)
    }

    fun enablePoll() = change { state ->
        if (state.isImportingMedia) state.copy(actionMessage = "添付ファイルの読み込み完了をお待ちください")
        else if (state.attachments.isNotEmpty()) state.copy(errorMessage = "メディアと投票は同時に追加できません")
        else state.copy(pollOptions = listOf("", ""))
    }

    fun disablePoll() = change { it.copy(pollOptions = emptyList()) }
    fun addPollOption() = change { if (it.pollOptions.size < 4) it.copy(pollOptions = it.pollOptions + "") else it }
    fun setPollOption(index: Int, value: String) = change { state ->
        state.copy(pollOptions = state.pollOptions.mapIndexed { i, current -> if (i == index) value else current })
    }
    fun setPollMultiple(value: Boolean) = change { it.copy(pollMultiple = value) }

    suspend fun loadEmojiHistory(): List<String> = _uiState.value.selectedSession?.let { session ->
        preferencesStore.composerEmojiHistory.first()[session.sessionId].orEmpty()
    }.orEmpty()

    suspend fun saveEmojiHistory(emojis: List<String>) {
        _uiState.value.selectedSession?.let { session ->
            preferencesStore.setComposerEmojiHistory(session.sessionId, emojis)
        }
    }

    fun recordUsedEmoji(emoji: String) {
        val sessionId = _uiState.value.selectedSession?.sessionId ?: return
        viewModelScope.launch { preferencesStore.recordComposerEmoji(sessionId, emoji) }
    }
    fun insertMention(account: StatusAuthor) {
        val mention = "@${account.accountName}"
        val current = _uiState.value.text
        if (!Regex("(^|\\s)${Regex.escape(mention)}(?=\\s|$)").containsMatchIn(current)) {
            onTextChanged(current.trimEnd() + current.takeIf(String::isNotBlank)?.let { " " }.orEmpty() + "$mention ")
        }
    }

    fun loadMentionCandidates() {
        val current = _uiState.value.selectedSession ?: return
        if (_uiState.value.isLoadingMentions || _uiState.value.mentionCandidates.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingMentions = true) }
        viewModelScope.launch {
            timelineRepository.getAccountList(current, current.accountId, followers = false).fold(
                onSuccess = { accounts -> _uiState.update { it.copy(mentionCandidates = accounts, isLoadingMentions = false) } },
                onFailure = { error -> _uiState.update { it.copy(isLoadingMentions = false, errorMessage = error.message ?: "候補を取得できませんでした") } },
            )
        }
    }

    fun restoreDraft(draft: ComposeDraft) {
        if (_uiState.value.isPosting) return
        if (waitForMediaImport()) return
        val session = _uiState.value.selectedSession ?: return
        if (draft.sessionId != session.sessionId || editStatusId != null || _uiState.value.isLoading) return
        stopMedia()
        activeReplyToId = draft.replyToId
        activeQuoteStatusId = draft.quotedStatusId
        activeQuoteStatusUrl = draft.quotedStatusUrl
        activeNativeQuote = draft.nativeQuote
        _uiState.update { state -> state.withDraft(draft).copy(
            drafts = state.drafts.filterNot { it.key == draft.key },
            quoteToStatus = null,
            quoteStatusId = draft.quotedStatusId,
            quotingNative = draft.quotedStatusId != null && draft.nativeQuote,
            isLoading = true,
        ) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // The attachment files now belong to the active composer; deleting the
                // saved draft must not delete them.
                withContext(NonCancellable) { preferencesStore.deleteDraft(draft.key) }
                _uiState.update { it.copy(isLoading = false, actionMessage = "下書きを取り出しました") }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(
                    isLoading = false,
                    drafts = (it.drafts + draft).sortedByDescending(ComposeDraft::updatedAtEpochMillis),
                    actionMessage = "下書きを一覧から削除できませんでした",
                ) }
            }
        }
        draft.replyToId?.let { viewModelScope.launch { loadReplyTarget(session, it, insertMention = false) } }
            ?: _uiState.update { it.copy(replyToStatus = null) }
        draft.quotedStatusId?.let { viewModelScope.launch { loadQuoteTarget(session, it) } }
        scheduleAttachments()
    }

    fun deleteDraft(draft: ComposeDraft) {
        if (waitForMediaImport()) return
        val session = _uiState.value.selectedSession ?: return
        if (draft.sessionId != session.sessionId) return
        viewModelScope.launch {
            preferencesStore.deleteDraft(draft.key)
            val retainedUris = _uiState.value.attachments.map(DraftAttachment::uri).toSet() +
                preferencesStore.drafts.first().flatMap(ComposeDraft::attachmentUris)
            draft.attachmentUris.filterNot(retainedUris::contains).forEach(deleteDraftFile)
            _uiState.update { state ->
                state.copy(
                    drafts = state.drafts.filterNot { it.key == draft.key },
                    actionMessage = "下書きを削除しました",
                )
            }
        }
    }

    fun consumeActionMessage() = _uiState.update { it.copy(actionMessage = null) }

    fun switchPostingAccount(sessionId: String) {
        if (activeQuoteStatusId != null) {
            _uiState.update { it.copy(actionMessage = "引用中は投稿元を切り替えられません") }
            return
        }
        if (editStatusId != null || _uiState.value.isImportingMedia || _uiState.value.isPosting) return
        val current = _uiState.value.selectedSession
        if (current?.sessionId == sessionId) return
        if (_uiState.value.sessions.none { it.sessionId == sessionId }) return
        stopMedia()
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            retainInput()
            val selected = _uiState.value.sessions.firstOrNull { it.sessionId == sessionId } ?: return@launch
            val preferences = preferencesStore.preferences.first()
            _uiState.update {
                ComposePostUiState(
                    sessions = it.sessions,
                    selectedSession = selected,
                    preferences = preferences,
                    visibility = preferences.forAccount(selected.sessionId).defaultVisibility,
                    drafts = preferencesStore.drafts.first().filter { draft -> draft.sessionId == selected.sessionId }
                        .sortedByDescending(ComposeDraft::updatedAtEpochMillis),
                    isLoading = true,
                )
            }
            loadAccountData(selected, restoreBuffer = true)
            activeReplyToId?.let { loadReplyTarget(selected, it, insertMention = _uiState.value.text.isBlank()) }
        }
    }

    fun dismissAltReminder() = _uiState.update { it.copy(altReminderVisible = false) }
    fun postIgnoringMissingAlt() { dismissAltReminder(); post(skipAltReminder = true) }

    fun post(skipAltReminder: Boolean = false) {
        val state = _uiState.value
        if (state.isPosting || state.isImportingMedia || state.isLoading || state.selectedSession == null) return
        if (state.text.isBlank() && state.attachments.isEmpty()) return
        if (state.quotingNative && (state.attachments.isNotEmpty() || state.pollOptions.isNotEmpty())) {
            _uiState.update { it.copy(errorMessage = "引用投稿にはメディアや投票を添付できません") }
            return
        }
        if (state.pollOptions.isNotEmpty() && state.pollOptions.any(String::isBlank)) {
            _uiState.update { it.copy(errorMessage = "投票の選択肢を入力してください") }
            return
        }
        if (!skipAltReminder && state.preferences.altTextReminder &&
            state.attachments.any { it.description.isBlank() }
        ) {
            _uiState.update { it.copy(altReminderVisible = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            val session = state.selectedSession
            scheduleAttachments()
            state.attachments.mapNotNull { mediaJobs[it.uri] }.forEach { it.join() }
            val attachments = _uiState.value.attachments
            if (attachments.any { it.transferState != MediaTransferState.Ready || it.mediaId == null }) {
                _uiState.update { it.copy(isPosting = false) }
                return@launch
            }
            val mediaIds = attachments.mapNotNull { it.mediaId }
            val request = CreateStatusRequest(
                text = state.text,
                replyToId = activeReplyToId,
                quotedStatusId = activeQuoteStatusId.takeIf { state.quotingNative },
                mediaIds = mediaIds,
                spoilerText = state.spoilerText,
                sensitive = state.sensitive || state.spoilerText.isNotBlank(),
                visibility = state.visibility.apiValue,
                language = null,
                pollOptions = state.pollOptions.map(String::trim).filter(String::isNotEmpty),
                pollExpiresInSeconds = state.pollExpiresInSeconds.takeIf { state.pollOptions.isNotEmpty() },
                pollMultiple = state.pollMultiple,
            )
            val result = if (editStatusId == null) {
                timelineRepository.createStatus(session, request, idempotencyKey)
            } else {
                timelineRepository.updateStatus(
                    session,
                    EditableStatus(
                        id = editStatusId,
                        text = request.text,
                        spoilerText = request.spoilerText,
                        sensitive = request.sensitive,
                        language = null,
                    ),
                )
            }
            result
                .onSuccess {
                    idempotencyKey = UUID.randomUUID().toString()
                    preferencesStore.deleteDraft(draftKey(session.sessionId))
                    preferencesStore.removeComposeBuffer(draftKey(session.sessionId))
                    state.attachments.forEach { deleteDraftFile(it.uri) }
                    _uiState.update { it.copy(isPosting = false, posted = true) }
                }
                .onFailure { showPostError(it, "投稿できませんでした") }
        }
    }

    fun saveDraft() {
        if (waitForMediaImport() || _uiState.value.isLoading) return
        viewModelScope.launch {
            saveDraftNow()
            _uiState.update { it.copy(actionMessage = "下書きに保存しました") }
        }
    }

    fun retainInputThen(onRetained: () -> Unit) {
        stopMedia()
        retainInput()
        onRetained()
    }
    fun discardDraft() {
        if (waitForMediaImport()) return
        stopMedia()
        val session = _uiState.value.selectedSession ?: return
        val attachments = _uiState.value.attachments
        val key = draftKey(session.sessionId)
        viewModelScope.launch {
            preferencesStore.deleteDraft(key)
            preferencesStore.removeComposeBuffer(key)
            attachments.forEach { deleteDraftFile(it.uri) }
        }
    }
    fun discardDraftThen(onDiscarded: () -> Unit) {
        if (waitForMediaImport()) return
        stopMedia()
        val session = _uiState.value.selectedSession ?: return onDiscarded()
        val attachments = _uiState.value.attachments
        val key = draftKey(session.sessionId)
        viewModelScope.launch {
            preferencesStore.deleteDraft(key)
            preferencesStore.removeComposeBuffer(key)
            attachments.forEach { deleteDraftFile(it.uri) }
            onDiscarded()
        }
    }

    private suspend fun loadAccountData(session: AccountSession, restoreBuffer: Boolean) {
        val draft = if (restoreBuffer) preferencesStore.getComposeBuffer(draftKey(session.sessionId)) else null
        val configuration = timelineRepository.getComposerConfiguration(session).getOrDefault(ComposerConfiguration())
        val emojis = timelineRepository.getCustomEmojis(session).getOrDefault(emptyList())
        _uiState.update { current ->
            if (current.selectedSession?.sessionId != session.sessionId) current else current.copy(
                text = draft?.text ?: current.text,
                spoilerText = draft?.spoilerText ?: current.spoilerText,
                visibility = draft?.visibility ?: current.visibility,
                sensitive = draft?.sensitive ?: current.sensitive,
                attachments = draft?.attachmentUris.orEmpty().map { uri ->
                    DraftAttachment(
                        uri = uri,
                        fileName = draft?.attachmentFileNames?.get(uri)
                            ?: uri.substringAfterLast('/').ifBlank { "attachment" },
                        mimeType = draft?.attachmentMimeTypes?.get(uri) ?: "application/octet-stream",
                        description = draft?.attachmentDescriptions?.get(uri).orEmpty(),
                        mediaId = draft?.attachmentMediaIds?.get(uri),
                    )
                },
                pollOptions = draft?.pollOptions ?: current.pollOptions,
                pollExpiresInSeconds = draft?.pollExpiresInSeconds ?: current.pollExpiresInSeconds,
                pollMultiple = draft?.pollMultiple ?: current.pollMultiple,
                configuration = configuration,
                customEmojis = emojis,
                isLoading = false,
            )
        }
        scheduleAttachments()
    }

    private fun change(transform: (ComposePostUiState) -> ComposePostUiState) {
        _uiState.update { transform(it).copy(errorMessage = null) }
    }

    private suspend fun saveDraftNow() {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        if (state.posted) return
        val hasContent = state.text.isNotBlank() || state.spoilerText.isNotBlank() ||
            state.attachments.isNotEmpty() || state.pollOptions.any(String::isNotBlank)
        if (!hasContent) {
            preferencesStore.deleteDraft(draftKey(session.sessionId))
            preferencesStore.removeComposeBuffer(draftKey(session.sessionId))
            return
        }
        preferencesStore.saveDraft(state.toDraft(session.sessionId))
        preferencesStore.removeComposeBuffer(draftKey(session.sessionId))
        _uiState.update { current ->
            current.copy(
                drafts = preferencesStore.drafts.first().filter { it.sessionId == session.sessionId }
                    .sortedByDescending(ComposeDraft::updatedAtEpochMillis),
            )
        }
    }

    private fun retainInput() {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        val key = draftKey(session.sessionId)
        val hasContent = state.text.isNotBlank() || state.spoilerText.isNotBlank() ||
            state.attachments.isNotEmpty() || state.pollOptions.any(String::isNotBlank)
        if (hasContent && !state.posted) {
            preferencesStore.retainComposeBuffer(state.toDraft(session.sessionId))
        } else {
            preferencesStore.removeComposeBuffer(key)
        }
    }

    private fun ComposePostUiState.toDraft(sessionId: String) = ComposeDraft(
        key = draftKey(sessionId),
        sessionId = sessionId,
        replyToId = activeReplyToId,
        quotedStatusId = activeQuoteStatusId,
        quotedStatusUrl = activeQuoteStatusUrl,
        nativeQuote = activeNativeQuote,
        text = text,
        spoilerText = spoilerText,
        visibility = visibility,
        sensitive = sensitive,
        attachmentUris = attachments.map(DraftAttachment::uri),
        attachmentFileNames = attachments.associate { it.uri to it.fileName },
        attachmentMimeTypes = attachments.associate { it.uri to it.mimeType },
        attachmentDescriptions = attachments.associate { it.uri to it.description },
        attachmentMediaIds = attachments.mapNotNull { item -> item.mediaId?.let { item.uri to it } }.toMap(),
        pollOptions = pollOptions,
        pollExpiresInSeconds = pollExpiresInSeconds,
        pollMultiple = pollMultiple,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    private fun draftKey(sessionId: String): String {
        val base = "$sessionId:${activeReplyToId ?: "new"}:${editStatusId.orEmpty()}"
        return activeQuoteStatusId?.let { "$base:quote-$it" } ?: base
    }

    private suspend fun loadQuoteTarget(session: AccountSession, statusId: String) {
        if (!activeNativeQuote && _uiState.value.text.isBlank()) {
            _uiState.update { it.copy(text = activeQuoteStatusUrl.orEmpty()) }
        }
        timelineRepository.getCachedStatus(session, statusId)?.let { cached ->
            _uiState.update { it.copy(quoteToStatus = cached) }
        } ?: timelineRepository.getStatusDetail(session, statusId).onSuccess { detail ->
            _uiState.update { it.copy(quoteToStatus = detail.status) }
        }
    }

    private suspend fun loadReplyTarget(session: AccountSession, statusId: String, insertMention: Boolean) {
        timelineRepository.getStatusDetail(session, statusId).onSuccess { detail ->
            _uiState.update { it.copy(replyToStatus = detail.status) }
            if (insertMention) insertMention(detail.status.author)
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = error.message ?: "返信先を取得できませんでした") }
        }
    }

    private fun ComposePostUiState.withDraft(draft: ComposeDraft) = copy(
        text = draft.text,
        spoilerText = draft.spoilerText,
        visibility = draft.visibility,
        sensitive = draft.sensitive,
        attachments = draft.attachmentUris.map { uri ->
            DraftAttachment(
                uri = uri,
                fileName = draft.attachmentFileNames[uri] ?: uri.substringAfterLast('/').ifBlank { "attachment" },
                mimeType = draft.attachmentMimeTypes[uri] ?: "application/octet-stream",
                description = draft.attachmentDescriptions[uri].orEmpty(),
                mediaId = draft.attachmentMediaIds[uri],
            )
        },
        pollOptions = draft.pollOptions,
        pollExpiresInSeconds = draft.pollExpiresInSeconds,
        pollMultiple = draft.pollMultiple,
    )

    private fun showPostError(error: Throwable, fallback: String) {
        val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
            "投稿またはメディア操作には追加権限が必要です。再ログインしてください。"
        } else error.message ?: fallback
        _uiState.update { it.copy(isPosting = false, errorMessage = message) }
    }

    override fun onCleared() {
        stopMedia()
        retainInput()
        super.onCleared()
    }

    class Factory(
        private val replyToId: String?,
        private val editStatusId: String?,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
        private val preferencesStore: UserPreferencesStore,
        private val deleteDraftFile: (String) -> Unit,
        private val draftMediaRepository: DraftMediaRepository,
        private val quoteStatusId: String? = null,
        private val quoteStatusUrl: String? = null,
        private val nativeQuote: Boolean = false,
        private val initialSharedText: String? = null,
        private val initialSharedMediaUri: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ComposePostViewModel(replyToId, editStatusId, timelineRepository, authRepository, preferencesStore,
                deleteDraftFile, draftMediaRepository, quoteStatusId, quoteStatusUrl, nativeQuote,
                initialSharedText, initialSharedMediaUri) as T
    }
}
