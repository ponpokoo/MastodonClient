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
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class DraftAttachment(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val description: String = "",
)

data class ComposePostUiState(
    val sessions: List<AccountSession> = emptyList(),
    val selectedSession: AccountSession? = null,
    val text: String = "",
    val spoilerText: String = "",
    val visibility: PostVisibility = PostVisibility.Public,
    val language: String = "ja",
    val sensitive: Boolean = false,
    val attachments: List<DraftAttachment> = emptyList(),
    val pollOptions: List<String> = emptyList(),
    val pollExpiresInSeconds: Long = 86_400,
    val pollMultiple: Boolean = false,
    val configuration: ComposerConfiguration = ComposerConfiguration(),
    val customEmojis: List<CustomEmoji> = emptyList(),
    val preferences: AppPreferences = AppPreferences(),
    val isLoading: Boolean = true,
    val isPosting: Boolean = false,
    val posted: Boolean = false,
    val errorMessage: String? = null,
    val altReminderVisible: Boolean = false,
)

class ComposePostViewModel(
    private val replyToId: String?,
    private val editStatusId: String?,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
    private val preferencesStore: UserPreferencesStore,
    private val deleteDraftFile: (String) -> Unit,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ComposePostUiState())
    val uiState: StateFlow<ComposePostUiState> = _uiState.asStateFlow()
    private var idempotencyKey = UUID.randomUUID().toString()
    private var autosaveJob: Job? = null

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
                    isLoading = false,
                )
            }
            selected?.let { session ->
                loadAccountData(session, restoreDraft = editStatusId == null)
                editStatusId?.let { statusId ->
                    timelineRepository.getEditableStatus(session, statusId).fold(
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
        if (text.length <= _uiState.value.configuration.maxCharacters) change { it.copy(text = text) }
    }

    fun onSpoilerChanged(text: String) = change { it.copy(spoilerText = text) }
    fun setVisibility(value: PostVisibility) = change { it.copy(visibility = value) }
    fun setLanguage(value: String) = change { it.copy(language = value.take(8)) }
    fun setSensitive(value: Boolean) = change { it.copy(sensitive = value) }

    fun addAttachments(items: List<DraftAttachment>) {
        val state = _uiState.value
        val remaining = (state.configuration.maxMediaAttachments - state.attachments.size).coerceAtLeast(0)
        change { it.copy(attachments = (it.attachments + items.take(remaining)).distinctBy(DraftAttachment::uri)) }
    }

    fun removeAttachment(uri: String) {
        deleteDraftFile(uri)
        change {
            it.copy(attachments = it.attachments.filterNot { attachment -> attachment.uri == uri })
        }
    }

    fun setAttachmentDescription(uri: String, description: String) = change { state ->
        state.copy(attachments = state.attachments.map {
            if (it.uri == uri) it.copy(description = description.take(state.configuration.mediaDescriptionLimit)) else it
        })
    }

    fun enablePoll() = change { state ->
        if (state.attachments.isNotEmpty()) state.copy(errorMessage = "メディアと投票は同時に追加できません")
        else state.copy(pollOptions = listOf("", ""))
    }

    fun disablePoll() = change { it.copy(pollOptions = emptyList()) }
    fun addPollOption() = change { if (it.pollOptions.size < 4) it.copy(pollOptions = it.pollOptions + "") else it }
    fun setPollOption(index: Int, value: String) = change { state ->
        state.copy(pollOptions = state.pollOptions.mapIndexed { i, current -> if (i == index) value else current })
    }
    fun setPollMultiple(value: Boolean) = change { it.copy(pollMultiple = value) }

    fun insertEmoji(shortcode: String) = onTextChanged(_uiState.value.text + ":$shortcode:")

    fun switchPostingAccount(sessionId: String) {
        if (editStatusId != null) return
        val current = _uiState.value.selectedSession
        if (current?.sessionId == sessionId) return
        viewModelScope.launch {
            saveDraftNow(force = true)
            val selected = _uiState.value.sessions.firstOrNull { it.sessionId == sessionId } ?: return@launch
            val preferences = preferencesStore.preferences.first()
            _uiState.update {
                ComposePostUiState(
                    sessions = it.sessions,
                    selectedSession = selected,
                    preferences = preferences,
                    visibility = preferences.forAccount(selected.sessionId).defaultVisibility,
                    isLoading = true,
                )
            }
            loadAccountData(selected, restoreDraft = true)
        }
    }

    fun dismissAltReminder() = _uiState.update { it.copy(altReminderVisible = false) }
    fun postIgnoringMissingAlt() { dismissAltReminder(); post(skipAltReminder = true) }

    fun post(skipAltReminder: Boolean = false) {
        val state = _uiState.value
        if (state.isPosting || state.selectedSession == null) return
        if (state.text.isBlank() && state.attachments.isEmpty()) return
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
            val mediaIds = mutableListOf<String>()
            for (attachment in state.attachments) {
                val filePath = android.net.Uri.parse(attachment.uri).path
                if (filePath.isNullOrBlank()) {
                    _uiState.update { it.copy(isPosting = false, errorMessage = "添付ファイルを読み込めません") }
                    return@launch
                }
                val uploaded = timelineRepository.uploadMedia(
                    session,
                    MediaUpload(attachment.fileName, attachment.mimeType, filePath, attachment.description),
                ).getOrElse { error ->
                    showPostError(error, "メディアをアップロードできませんでした")
                    return@launch
                }
                mediaIds += uploaded.id
            }
            val request = CreateStatusRequest(
                text = state.text,
                replyToId = replyToId,
                mediaIds = mediaIds,
                spoilerText = state.spoilerText,
                sensitive = state.sensitive,
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
                        language = request.language.orEmpty().ifBlank { "ja" },
                    ),
                )
            }
            result
                .onSuccess {
                    idempotencyKey = UUID.randomUUID().toString()
                    preferencesStore.deleteDraft(draftKey(session.sessionId))
                    state.attachments.forEach { deleteDraftFile(it.uri) }
                    _uiState.update { it.copy(isPosting = false, posted = true) }
                }
                .onFailure { showPostError(it, "投稿できませんでした") }
        }
    }

    fun saveDraft() { viewModelScope.launch { saveDraftNow(force = true) } }
    fun saveDraftThen(onSaved: () -> Unit) {
        viewModelScope.launch {
            saveDraftNow(force = true)
            onSaved()
        }
    }
    fun discardDraft() {
        val session = _uiState.value.selectedSession ?: return
        val attachments = _uiState.value.attachments
        viewModelScope.launch {
            preferencesStore.deleteDraft(draftKey(session.sessionId))
            attachments.forEach { deleteDraftFile(it.uri) }
        }
    }
    fun discardDraftThen(onDiscarded: () -> Unit) {
        val session = _uiState.value.selectedSession ?: return onDiscarded()
        val attachments = _uiState.value.attachments
        viewModelScope.launch {
            preferencesStore.deleteDraft(draftKey(session.sessionId))
            attachments.forEach { deleteDraftFile(it.uri) }
            onDiscarded()
        }
    }

    fun clearComposer() {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        state.attachments.forEach { deleteDraftFile(it.uri) }
        viewModelScope.launch { preferencesStore.deleteDraft(draftKey(session.sessionId)) }
        _uiState.update {
            it.copy(
                text = "", spoilerText = "", attachments = emptyList(), pollOptions = emptyList(),
                sensitive = false, errorMessage = null, altReminderVisible = false,
            )
        }
    }

    private suspend fun loadAccountData(session: AccountSession, restoreDraft: Boolean) {
        val draft = if (restoreDraft) preferencesStore.drafts.first()
            .firstOrNull { it.key == draftKey(session.sessionId) } else null
        val configuration = timelineRepository.getComposerConfiguration(session).getOrDefault(ComposerConfiguration())
        val emojis = timelineRepository.getCustomEmojis(session).getOrDefault(emptyList())
        _uiState.update { current ->
            if (current.selectedSession?.sessionId != session.sessionId) current else current.copy(
                text = draft?.text ?: current.text,
                spoilerText = draft?.spoilerText ?: current.spoilerText,
                visibility = draft?.visibility ?: current.visibility,
                language = draft?.language ?: current.language,
                sensitive = draft?.sensitive ?: current.sensitive,
                attachments = draft?.attachmentUris.orEmpty().map { uri ->
                    DraftAttachment(
                        uri = uri,
                        fileName = draft?.attachmentFileNames?.get(uri)
                            ?: uri.substringAfterLast('/').ifBlank { "attachment" },
                        mimeType = draft?.attachmentMimeTypes?.get(uri) ?: "application/octet-stream",
                        description = draft?.attachmentDescriptions?.get(uri).orEmpty(),
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
    }

    private fun change(transform: (ComposePostUiState) -> ComposePostUiState) {
        _uiState.update { transform(it).copy(errorMessage = null) }
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        if (!_uiState.value.preferences.draftAutosave) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { delay(450); saveDraftNow() }
    }

    private suspend fun saveDraftNow(force: Boolean = false) {
        val state = _uiState.value
        val session = state.selectedSession ?: return
        if ((!force && !state.preferences.draftAutosave) || state.posted) return
        val hasContent = state.text.isNotBlank() || state.spoilerText.isNotBlank() ||
            state.attachments.isNotEmpty() || state.pollOptions.any(String::isNotBlank)
        if (!hasContent) {
            preferencesStore.deleteDraft(draftKey(session.sessionId))
            return
        }
        preferencesStore.saveDraft(
            ComposeDraft(
                key = draftKey(session.sessionId),
                sessionId = session.sessionId,
                replyToId = replyToId,
                text = state.text,
                spoilerText = state.spoilerText,
                visibility = state.visibility,
                language = state.language,
                sensitive = state.sensitive,
                attachmentUris = state.attachments.map(DraftAttachment::uri),
                attachmentFileNames = state.attachments.associate { it.uri to it.fileName },
                attachmentMimeTypes = state.attachments.associate { it.uri to it.mimeType },
                attachmentDescriptions = state.attachments.associate { it.uri to it.description },
                pollOptions = state.pollOptions,
                pollExpiresInSeconds = state.pollExpiresInSeconds,
                pollMultiple = state.pollMultiple,
            ),
        )
    }

    private fun draftKey(sessionId: String) = "$sessionId:${replyToId ?: "new"}:${editStatusId.orEmpty()}"

    private fun showPostError(error: Throwable, fallback: String) {
        val message = if ((error as? HttpException)?.code() in setOf(401, 403)) {
            "投稿またはメディア操作には追加権限が必要です。再ログインしてください。"
        } else error.message ?: fallback
        _uiState.update { it.copy(isPosting = false, errorMessage = message) }
    }

    override fun onCleared() {
        if (_uiState.value.preferences.draftAutosave) saveDraft()
        super.onCleared()
    }

    class Factory(
        private val replyToId: String?,
        private val editStatusId: String?,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
        private val preferencesStore: UserPreferencesStore,
        private val deleteDraftFile: (String) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ComposePostViewModel(replyToId, editStatusId, timelineRepository, authRepository, preferencesStore, deleteDraftFile) as T
    }
}
