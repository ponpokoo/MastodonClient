package io.github.ponpokoo.mastodonclient.feature.compose

import io.github.ponpokoo.mastodonclient.domain.model.requiresAuthentication
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.model.ComposerConfiguration
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import io.github.ponpokoo.mastodonclient.domain.model.EditableStatus
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment
import io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult
import io.github.ponpokoo.mastodonclient.domain.model.MediaValidator
import io.github.ponpokoo.mastodonclient.domain.model.MediaRejectionReason
import io.github.ponpokoo.mastodonclient.domain.model.RejectedMedia
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import io.github.ponpokoo.mastodonclient.domain.model.MediaTransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

data class ComposePostUiState(
    val sessions: List<AccountSession> = emptyList(),
    val selectedSession: AccountSession? = null,
    val text: String = "",
    val spoilerText: String = "",
    val visibility: PostVisibility = PostVisibility.Public,
    val sensitive: Boolean = false,
    val language: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long = 86_400,
    val pollMultiple: Boolean = false,
    val configuration: ComposerConfiguration = ComposerConfiguration(),
    val customEmojis: List<CustomEmoji> = emptyList(),
    val drafts: List<ComposeDraft> = emptyList(),
    val replyToStatus: TimelineStatus? = null,
    val replyToId: String? = null,
    val quoteToStatus: TimelineStatus? = null,
    val quoteStatusId: String? = null,
    val quotingNative: Boolean = false,
    val mentionCandidates: List<StatusAuthor> = emptyList(),
    val isLoadingMentions: Boolean = false,
    val preferences: AppPreferences = AppPreferences(),
    val isLoading: Boolean = true,
    val isPosting: Boolean = false,
    val isSavingDraft: Boolean = false,
    val isImportingMedia: Boolean = false,
    val pendingSharedMediaCount: Int = 0,
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
    private val initialSharedMediaUris: List<String> = emptyList(),
    private val logMediaFailure: (String) -> Unit = { android.util.Log.w("MediaUpload", it) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(ComposePostUiState(replyToId = initialReplyToId))
    val uiState: StateFlow<ComposePostUiState> = _uiState.asStateFlow()
    private var idempotencyKey = UUID.randomUUID().toString()
    private var activeReplyToId: String? = initialReplyToId
    private enum class ReferenceKind { Reply, Quote }
    private val targetJobs = mutableMapOf<ReferenceKind, Job>()
    private val targetGenerations = mutableMapOf<ReferenceKind, Int>()
    private var activeQuoteStatusId: String? = initialQuoteStatusId
    private var activeQuoteStatusUrl: String? = initialQuoteStatusUrl
    private var activeNativeQuote: Boolean = nativeQuote
    private var initialShareApplied = false
    private var pendingSharedMedia: MediaImportResult? = null
    private val mediaTransfer = ComposeMediaTransfer(timelineRepository, viewModelScope,
        selectedSession = { _uiState.value.selectedSession },
        attachment = { uri -> _uiState.value.attachments.find { it.uri == uri } },
        validationError = { uri -> attachmentValidationErrors()[uri] },
        updateAttachment = { uri, transform -> _uiState.update { state -> state.copy(
            attachments = state.attachments.map { if (it.uri == uri) transform(it) else it }) } },
        posting = _uiState.map { it.isPosting }, logMediaFailure = logMediaFailure)
    private var restoredDraftKey: String? = null
    private var postingJob: Job? = null

    private fun stopMedia() = mediaTransfer.stop()

    private fun scheduleAttachments() {
        val errors = attachmentValidationErrors()
        _uiState.value.attachments.forEach { item ->
            val error = errors[item.uri]
            if (error != null) {
                mediaTransfer.cancel(item.uri)
                _uiState.update { state -> state.copy(attachments = state.attachments.map {
                    if (it.uri == item.uri) it.copy(transferState = MediaTransferState.Failed,
                        progress = null, errorMessage = error, errorDetail = null, validationError = true) else it
                }) }
            } else {
                if (item.validationError) _uiState.update { state -> state.copy(
                    attachments = state.attachments.map {
                        if (it.uri == item.uri) it.copy(transferState = MediaTransferState.Waiting,
                            errorMessage = null, errorDetail = null, validationError = false) else it
                    },
                ) }
                scheduleMedia(item.uri)
            }
        }
    }

    private fun attachmentValidationErrors(): Map<String, String> {
        val state = _uiState.value
        val accepted = mutableListOf<DraftAttachment>()
        return buildMap {
            state.attachments.forEach { item ->
                val result = MediaValidator.validate(listOf(item), accepted, state.configuration)
                val error = when {
                    state.quotingNative -> "引用投稿にはメディアを添付できません"
                    state.pollOptions.isNotEmpty() -> "メディアと投票は同時に追加できません"
                    else -> result.rejected.firstOrNull()?.let(::rejectionMessage)
                }
                if (error == null) accepted += result.attachments else put(item.uri, error)
            }
        }
    }

    fun retryMedia(uri: String) {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting) return
        if (_uiState.value.configuration.supportedMimeTypes == null) {
            retryMediaConfiguration()
            return
        }
        scheduleMedia(uri, retry = true)
    }

    private fun scheduleMedia(uri: String, retry: Boolean = false) = mediaTransfer.schedule(uri, retry)

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
                observeAccountDisplay()
                loadAccountData(session, restoreBuffer = editStatusId == null, sharedMediaUris = initialSharedMediaUris)
                if (_uiState.value.selectedSession?.hasSameCredentials(session) != true) return@launch
                applyInitialShare()
                consumePendingShare()
                loadQuoteTarget(session)
                editStatusId?.let { statusId ->
                    val result = timelineRepository.getEditableStatus(session, statusId)
                    currentCoroutineContext().ensureActive()
                    if (_uiState.value.selectedSession?.hasSameCredentials(session) != true) return@launch
                    result.fold(
                        onSuccess = { source -> _uiState.update { state -> state.copy(
                            text = source.text,
                            spoilerText = source.spoilerText,
                            sensitive = source.sensitive,
                            language = source.language,
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
        val state = _uiState.value
        if (state.isSavingDraft || state.isPosting || text.length > state.configuration.maxCharacters) return
        val author = state.replyToStatus?.author
        if (author != null && containsMention(state.text, author) && !containsMention(text, author)) {
            clearReply()
        }
        activeQuoteStatusUrl?.takeIf { !activeNativeQuote }?.let { url ->
            if (containsQuoteUrl(state.text, url) && !containsQuoteUrl(text, url)) clearQuote()
        }
        change { it.copy(text = text) }
    }

    private fun observeAccountDisplay() {
        viewModelScope.launch {
            authRepository.observeSessions().collect { sessions ->
                val selected = _uiState.value.selectedSession
                if (selected != null && sessions.none { it.hasSameCredentials(selected) }) {
                    postingJob?.cancel()
                    stopMedia()
                    clearPendingShare()
                    ReferenceKind.entries.forEach(::cancelTargetLoad)
                    _uiState.update { ComposePostUiState(sessions = sessions, preferences = it.preferences,
                        isLoading = false, errorMessage = "投稿元のアカウント登録が変更されました") }
                    return@collect
                }
                _uiState.update { state ->
                    val current = state.selectedSession
                    val updated = sessions.firstOrNull { it.sessionId == current?.sessionId }
                    state.copy(sessions = sessions,
                        selectedSession = if (current?.hasSameCredentials(updated) == true) updated else current)
                }
            }
        }
    }

    private fun containsMention(text: String, author: StatusAuthor): Boolean =
        Regex("(?<![\\p{L}\\p{N}_@])@${Regex.escape(author.accountName)}(?![\\p{L}\\p{N}_@]|\\.[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    private fun containsQuoteUrl(text: String, url: String): Boolean = url.isNotBlank() &&
        Regex("(?<![A-Za-z0-9_:/?=&%+~@.-])${Regex.escape(url)}(?![A-Za-z0-9_/?#=&%+~@-]|\\.[A-Za-z0-9])")
            .containsMatchIn(text)

    fun clearReply() = clearReference(ReferenceKind.Reply)

    fun clearQuote() = clearReference(ReferenceKind.Quote)

    private fun clearReference(kind: ReferenceKind) {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting || targetId(kind) == null) return
        _uiState.value.selectedSession?.let { preferencesStore.removeComposeBuffer(draftKey(it.sessionId)) }
        cancelTargetLoad(kind)
        when (kind) {
            ReferenceKind.Reply -> {
                activeReplyToId = null
                _uiState.update { it.copy(replyToId = null, replyToStatus = null, errorMessage = null) }
            }
            ReferenceKind.Quote -> {
                activeQuoteStatusId = null
                activeQuoteStatusUrl = null
                activeNativeQuote = false
                _uiState.update { it.copy(quoteStatusId = null, quoteToStatus = null, quotingNative = false, errorMessage = null) }
            }
        }
    }

    fun onSpoilerChanged(text: String) = change { it.copy(spoilerText = text) }
    fun setVisibility(value: PostVisibility) = change { it.copy(visibility = value) }
    fun setSensitive(value: Boolean) = change { it.copy(sensitive = value) }
    fun setLanguage(value: String?) {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting || _uiState.value.isLoading) return
        change { it.copy(language = value) }
    }

    fun importMedia(uris: List<String>) {
        val state = _uiState.value
        if (state.isSavingDraft || state.isImportingMedia || state.isPosting || state.isLoading || state.selectedSession == null) return
        if (uris.isEmpty()) return
        if (state.configuration.supportedMimeTypes == null) {
            _uiState.update { it.copy(errorMessage = "サーバーの対応ファイル形式を確認できません。設定を再取得してください") }
            return
        }
        if (state.pollOptions.isNotEmpty() || state.quotingNative) {
            _uiState.update { it.copy(errorMessage = if (state.quotingNative)
                "引用投稿にはメディアを添付できません" else "メディアと投票は同時に追加できません") }
            return
        }
        val sessionId = state.selectedSession.sessionId
        val generation = mediaTransfer.generation
        _uiState.update { it.copy(isImportingMedia = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val imported = stageMedia(uris)
                if (generation != mediaTransfer.generation || _uiState.value.selectedSession?.sessionId != sessionId) {
                    imported.attachments.forEach { deleteDraftFile(it.uri) }
                    return@launch
                }
                addImportedMedia(imported)
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
    }

    private suspend fun stageMedia(uris: List<String>): MediaImportResult {
        val session = _uiState.value.selectedSession ?: return MediaImportResult()
        val result = draftMediaRepository.importMedia(session.sessionId, uris.distinct()).getOrElse { error ->
            if (error is CancellationException) throw error
            MediaImportResult(rejected = uris.distinct().map {
                RejectedMedia("attachment", null, MediaRejectionReason.Unreadable)
            })
        }
        try { currentCoroutineContext().ensureActive() } catch (error: CancellationException) {
            result.attachments.forEach { deleteDraftFile(it.uri) }
            throw error
        }
        return result
    }

    private fun consumePendingShare() {
        val imported = pendingSharedMedia ?: return
        if (_uiState.value.configuration.supportedMimeTypes == null && imported.attachments.isNotEmpty()) {
            _uiState.update { it.copy(pendingSharedMediaCount = imported.attachments.size,
                errorMessage = "共有ファイルを読み込みました。サーバー設定を再取得して添付してください") }
            return
        }
        pendingSharedMedia = null
        _uiState.update { it.copy(pendingSharedMediaCount = 0) }
        addImportedMedia(imported)
    }

    private fun clearPendingShare() {
        pendingSharedMedia?.attachments?.forEach { deleteDraftFile(it.uri) }
        pendingSharedMedia = null
        _uiState.update { it.copy(pendingSharedMediaCount = 0) }
    }

    fun discardPendingSharedMedia() {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting || _uiState.value.isLoading || _uiState.value.isImportingMedia) return
        clearPendingShare()
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun retryMediaConfiguration() {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        if (state.isSavingDraft || state.isPosting || state.isLoading || state.isImportingMedia) return
        val generation = mediaTransfer.generation
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val configuration = timelineRepository.getComposerConfiguration(session).getOrDefault(ComposerConfiguration())
            currentCoroutineContext().ensureActive()
            if (generation != mediaTransfer.generation || _uiState.value.selectedSession?.sessionId != session.sessionId) return@launch
            _uiState.update { it.copy(configuration = configuration, isLoading = false,
                errorMessage = if (configuration.supportedMimeTypes == null)
                    "サーバーの対応ファイル形式を確認できません。添付は送信されません" else null) }
            consumePendingShare()
            scheduleAttachments()
        }
    }

    private fun waitForMediaImport(): Boolean {
        if (!_uiState.value.isImportingMedia) return false
        _uiState.update { it.copy(actionMessage = "添付ファイルの読み込み完了をお待ちください") }
        return true
    }

    private fun addImportedMedia(imported: MediaImportResult) {
        val state = _uiState.value
        if (state.pollOptions.isNotEmpty() || state.quotingNative) {
            imported.attachments.forEach { deleteDraftFile(it.uri) }
            _uiState.update { it.copy(errorMessage = if (state.quotingNative)
                "引用投稿にはメディアを添付できません" else "メディアと投票は同時に追加できません") }
            return
        }
        val result = MediaValidator.validate(imported.attachments, state.attachments, state.configuration)
        val acceptedUris = result.attachments.map(DraftAttachment::uri).toSet()
        imported.attachments.filterNot { it.uri in acceptedUris }.forEach { deleteDraftFile(it.uri) }
        val rejected = imported.rejected + result.rejected
        val message = if (rejected.isEmpty()) null else buildString {
            append("${imported.attachments.size + imported.rejected.size}件中${result.attachments.size}件を添付しました。")
            rejected.groupBy(::rejectionMessage).forEach { (reason, items) ->
                append("\n${items.size}件：$reason\n${items.joinToString { it.fileName.take(80) }}")
            }
        }
        _uiState.update { it.copy(attachments = (it.attachments + result.attachments).distinctBy(DraftAttachment::uri),
            errorMessage = message, actionMessage = if (rejected.isNotEmpty() && result.attachments.isNotEmpty())
                "${result.attachments.size}件を添付しました。${rejected.size}件は添付できませんでした" else it.actionMessage) }
        scheduleAttachments()
    }

    private fun rejectionMessage(item: RejectedMedia): String = when (item.reason) {
        MediaRejectionReason.UnknownType -> "ファイル形式を判別できません"
        MediaRejectionReason.UnsupportedType -> "このファイル形式はサーバーでサポートされていません（${item.mimeType}）"
        MediaRejectionReason.Unreadable -> "ファイルを読み込めません"
        MediaRejectionReason.PermissionDenied -> "ファイルの読み取り権限がありません。もう一度共有してください"
        MediaRejectionReason.TooManyAttachments -> "サーバーの添付上限を超えています"
        MediaRejectionReason.IncompatibleCombination -> "動画・音声は他のメディアと同時に添付できません"
        MediaRejectionReason.ConfigurationUnavailable -> "サーバーの対応ファイル形式を確認できません。設定を再取得してください"
    }

    fun removeAttachment(uri: String) {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting) return
        mediaTransfer.cancel(uri)
        deleteDraftFile(uri)
        change {
            it.copy(attachments = it.attachments.filterNot { attachment -> attachment.uri == uri })
        }
        scheduleAttachments()
    }

    fun setAttachmentDescription(uri: String, description: String) {
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting) return
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
        if (_uiState.value.isSavingDraft || _uiState.value.isPosting) return
        if (waitForMediaImport()) return
        val session = _uiState.value.selectedSession ?: return
        if (draft.sessionId != session.sessionId || editStatusId != null || _uiState.value.isLoading) return
        stopMedia()
        ReferenceKind.entries.forEach(::cancelTargetLoad)
        restoredDraftKey = draft.key
        activeReplyToId = draft.replyToId
        activeQuoteStatusId = draft.quotedStatusId
        activeQuoteStatusUrl = draft.quotedStatusUrl
        activeNativeQuote = draft.nativeQuote
        _uiState.update { state -> state.withDraft(draft).copy(
            replyToId = activeReplyToId,
            replyToStatus = null,
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
        loadReplyTarget(session, insertMention = false)
        loadQuoteTarget(session)
        scheduleAttachments()
    }

    fun deleteDraft(draft: ComposeDraft) {
        if (_uiState.value.isSavingDraft) return
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
        if (_uiState.value.isSavingDraft || editStatusId != null || _uiState.value.isImportingMedia || _uiState.value.isPosting || _uiState.value.isLoading) return
        val current = _uiState.value.selectedSession
        if (current?.sessionId == sessionId) return
        if (_uiState.value.sessions.none { it.sessionId == sessionId }) return
        ReferenceKind.entries.forEach(::cancelTargetLoad)
        val replyText = _uiState.value.text.takeIf { activeReplyToId != null }
        if (replyText != null) clearReply()
        stopMedia()
        clearPendingShare()
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            retainInput()
            restoredDraftKey = null
            val selected = _uiState.value.sessions.firstOrNull { it.sessionId == sessionId } ?: return@launch
            val preferences = preferencesStore.preferences.first()
            _uiState.update {
                ComposePostUiState(
                    sessions = it.sessions,
                    selectedSession = selected,
                    text = replyText.orEmpty(),
                    preferences = preferences,
                    visibility = preferences.forAccount(selected.sessionId).defaultVisibility,
                    drafts = preferencesStore.drafts.first().filter { draft -> draft.sessionId == selected.sessionId }
                        .sortedByDescending(ComposeDraft::updatedAtEpochMillis),
                    isLoading = true,
                )
            }
            loadAccountData(selected, restoreBuffer = replyText == null)
        }
    }

    fun dismissAltReminder() = _uiState.update { it.copy(altReminderVisible = false) }
    fun postIgnoringMissingAlt() { dismissAltReminder(); post(skipAltReminder = true) }

    fun post(skipAltReminder: Boolean = false) {
        val state = _uiState.value
        if (state.isSavingDraft || state.isPosting || state.isImportingMedia || state.isLoading || state.selectedSession == null) return
        if (activeReplyToId != null && state.replyToStatus == null) {
            _uiState.update { it.copy(errorMessage = "返信先の読み込みが完了してから投稿してください") }
            return
        }
        if (state.text.isBlank() && state.attachments.isEmpty()) return
        if (attachmentValidationErrors().isNotEmpty() || pendingSharedMedia != null) {
            scheduleAttachments()
            _uiState.update { it.copy(errorMessage = "添付できないファイルがあります。設定の再取得または添付の削除を行ってください") }
            return
        }
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
        postingJob = viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            val session = state.selectedSession
            scheduleAttachments()
            mediaTransfer.await(state.attachments.map { it.uri })
            if (_uiState.value.selectedSession?.hasSameCredentials(session) != true ||
                authRepository.getSessions().none { it.hasSameCredentials(session) }) {
                _uiState.update { it.copy(isPosting = false) }
                return@launch
            }
            val attachments = _uiState.value.attachments
            if (attachmentValidationErrors().isNotEmpty()) {
                scheduleAttachments()
                _uiState.update { it.copy(isPosting = false) }
                return@launch
            }
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
                language = state.language,
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
                        language = state.language,
                    ),
                )
            }
            currentCoroutineContext().ensureActive()
            result
                .onSuccess {
                    idempotencyKey = UUID.randomUUID().toString()
                    restoredDraftKey?.let { preferencesStore.deleteDraft(it) }
                    preferencesStore.removeComposeBuffer(draftKey(session.sessionId))
                    state.attachments.forEach { deleteDraftFile(it.uri) }
                    _uiState.update { it.copy(isPosting = false, posted = true) }
                }
                .onFailure { showPostError(it, "投稿できませんでした") }
        }
    }

    fun saveDraft() {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        if (waitForMediaImport() || state.isLoading || state.isPosting || state.isSavingDraft || state.posted) return
        if (state.pendingSharedMediaCount > 0) {
            _uiState.update { it.copy(errorMessage = "共有ファイルを添付または削除してから保存してください") }
            return
        }
        if (state.text.isBlank() && state.spoilerText.isBlank() && state.attachments.isEmpty() &&
            state.pollOptions.none(String::isNotBlank)) return
        val bufferKey = draftKey(session.sessionId)
        val draft = state.toDraft(session.sessionId).copy(key = restoredDraftKey ?: UUID.randomUUID().toString())
        _uiState.update { it.copy(isSavingDraft = true, errorMessage = null, actionMessage = null) }
        viewModelScope.launch {
            try {
                preferencesStore.saveDraft(draft)
                currentCoroutineContext().ensureActive()
                if (authRepository.getSessions().none { it.hasSameCredentials(session) }) return@launch
                stopMedia()
                ReferenceKind.entries.forEach(::cancelTargetLoad)
                preferencesStore.removeComposeBuffer(bufferKey)
                activeReplyToId = null
                activeQuoteStatusId = null
                activeQuoteStatusUrl = null
                activeNativeQuote = false
                restoredDraftKey = null
                idempotencyKey = UUID.randomUUID().toString()
                _uiState.update { current -> ComposePostUiState(
                    sessions = current.sessions,
                    selectedSession = session,
                    preferences = current.preferences,
                    visibility = current.preferences.forAccount(session.sessionId).defaultVisibility,
                    configuration = current.configuration,
                    customEmojis = current.customEmojis,
                    drafts = (current.drafts.filterNot { it.key == draft.key } + draft)
                        .sortedByDescending(ComposeDraft::updatedAtEpochMillis),
                    isLoading = false,
                    actionMessage = "下書きに保存しました",
                ) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "下書きを保存できませんでした。もう一度お試しください") }
            } finally {
                _uiState.update { it.copy(isSavingDraft = false) }
            }
        }
    }

    fun retainInputThen(onRetained: () -> Unit) {
        if (_uiState.value.isSavingDraft) return
        stopMedia()
        retainInput()
        onRetained()
    }
    fun discardDraft() {
        if (_uiState.value.isSavingDraft) return
        if (waitForMediaImport()) return
        stopMedia()
        val session = _uiState.value.selectedSession ?: return
        val attachments = _uiState.value.attachments
        val key = draftKey(session.sessionId)
        viewModelScope.launch {
            restoredDraftKey?.let { preferencesStore.deleteDraft(it) }
            preferencesStore.removeComposeBuffer(key)
            attachments.forEach { deleteDraftFile(it.uri) }
        }
    }
    fun discardDraftThen(onDiscarded: () -> Unit) {
        if (_uiState.value.isSavingDraft) return
        if (waitForMediaImport()) return
        stopMedia()
        val session = _uiState.value.selectedSession ?: return onDiscarded()
        val attachments = _uiState.value.attachments
        val key = draftKey(session.sessionId)
        viewModelScope.launch {
            restoredDraftKey?.let { preferencesStore.deleteDraft(it) }
            preferencesStore.removeComposeBuffer(key)
            attachments.forEach { deleteDraftFile(it.uri) }
            onDiscarded()
        }
    }

    private suspend fun loadAccountData(session: AccountSession, restoreBuffer: Boolean, sharedMediaUris: List<String> = emptyList()) {
        val generation = mediaTransfer.generation
        val draft = if (restoreBuffer) preferencesStore.getComposeBuffer(draftKey(session.sessionId)) else null
        if (draft != null) {
            activeReplyToId = draft.replyToId
            _uiState.update { it.withDraft(draft).copy(replyToId = activeReplyToId) }
        }
        // Restore existing input first, then copy shared files before any network waits.
        if (sharedMediaUris.isNotEmpty()) {
            _uiState.update { it.copy(isImportingMedia = true) }
            try { pendingSharedMedia = stageMedia(sharedMediaUris) }
            finally { _uiState.update { it.copy(isImportingMedia = false) } }
        }
        loadReplyTarget(session, insertMention = draft == null && _uiState.value.text.isBlank())
        val configuration = timelineRepository.getComposerConfiguration(session).getOrDefault(ComposerConfiguration())
        val emojis = timelineRepository.getCustomEmojis(session).getOrDefault(emptyList())
        currentCoroutineContext().ensureActive()
        if (generation != mediaTransfer.generation || _uiState.value.selectedSession?.sessionId != session.sessionId) return
        _uiState.update { current ->
            if (current.selectedSession?.sessionId != session.sessionId) current else current.copy(
                text = current.text,
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
                errorMessage = if (configuration.supportedMimeTypes == null && (current.attachments.isNotEmpty() || pendingSharedMedia != null))
                    "サーバーの対応ファイル形式を確認できません。添付には設定の再取得が必要です" else current.errorMessage,
                isLoading = false,
            )
        }
        scheduleAttachments()
    }

    private fun change(transform: (ComposePostUiState) -> ComposePostUiState) {
        if (_uiState.value.isSavingDraft) return
        _uiState.update { transform(it).copy(errorMessage = null) }
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
        language = language,
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
        return activeQuoteStatusId?.let {
            "$base:quote-$it:${if (activeNativeQuote) "native" else "link"}"
        } ?: base
    }

    private fun loadQuoteTarget(session: AccountSession) {
        if (activeQuoteStatusId == null) return
        if (!activeNativeQuote && _uiState.value.text.isBlank()) {
            _uiState.update { it.copy(text = activeQuoteStatusUrl.orEmpty()) }
        }
        loadTarget(session, ReferenceKind.Quote) { status ->
            _uiState.update { it.copy(quoteToStatus = status) }
        }
    }

    private fun loadReplyTarget(session: AccountSession, insertMention: Boolean) {
        val originalText = _uiState.value.text
        loadTarget(session, ReferenceKind.Reply) { status ->
            if (containsMention(originalText, status.author) && !containsMention(_uiState.value.text, status.author)) {
                clearReply()
            } else {
                _uiState.update { it.copy(replyToId = activeReplyToId, replyToStatus = status) }
                if (insertMention && _uiState.value.text == originalText) insertMention(status.author)
            }
        }
    }

    private fun cancelTargetLoad(kind: ReferenceKind): Int {
        val generation = (targetGenerations[kind] ?: 0) + 1
        targetGenerations[kind] = generation
        targetJobs.remove(kind)?.cancel()
        return generation
    }

    private fun targetId(kind: ReferenceKind): String? = when (kind) {
        ReferenceKind.Reply -> activeReplyToId
        ReferenceKind.Quote -> activeQuoteStatusId
    }

    private fun loadTarget(session: AccountSession, kind: ReferenceKind, apply: (TimelineStatus) -> Unit) {
        val generation = cancelTargetLoad(kind)
        val statusId = targetId(kind) ?: return
        fun isCurrent() = generation == targetGenerations[kind] && targetId(kind) == statusId &&
            _uiState.value.selectedSession?.sessionId == session.sessionId
        timelineRepository.getCachedStatus(session, statusId)?.let {
            if (isCurrent()) apply(it)
            return
        }
        targetJobs[kind] = viewModelScope.launch {
            // Both previews need only the target post, not its conversation.
            val result = timelineRepository.getTimelineStatus(session, statusId)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return@launch
            result.onSuccess(apply).onFailure { error ->
                if (error is CancellationException) throw error
                val fallback = if (kind == ReferenceKind.Reply) "返信先を取得できませんでした" else "引用元を取得できませんでした"
                _uiState.update { it.copy(errorMessage = error.message ?: fallback) }
            }
        }
    }

    private fun ComposePostUiState.withDraft(draft: ComposeDraft) = copy(
        text = draft.text,
        spoilerText = draft.spoilerText,
        visibility = draft.visibility,
        sensitive = draft.sensitive,
        language = draft.language,
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
        val message = if (error.requiresAuthentication) {
            "投稿またはメディア操作には追加権限が必要です。再ログインしてください。"
        } else error.message ?: fallback
        _uiState.update { it.copy(isPosting = false, errorMessage = message) }
    }

    override fun onCleared() {
        stopMedia()
        clearPendingShare()
        ReferenceKind.entries.forEach(::cancelTargetLoad)
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
        private val initialSharedMediaUris: List<String> = emptyList(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ComposePostViewModel(replyToId, editStatusId, timelineRepository, authRepository, preferencesStore,
                deleteDraftFile, draftMediaRepository, quoteStatusId, quoteStatusUrl, nativeQuote,
                initialSharedText, initialSharedMediaUris) as T
    }
}
