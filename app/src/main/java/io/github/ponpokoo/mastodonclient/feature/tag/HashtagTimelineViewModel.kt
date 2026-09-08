package io.github.ponpokoo.mastodonclient.feature.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HashtagTimelineUiState(
    val statuses: List<TimelineStatus> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val errorMessage: String? = null,
)

class HashtagTimelineViewModel(
    private val hashtag: String,
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HashtagTimelineUiState())
    val uiState: StateFlow<HashtagTimelineUiState> = _uiState.asStateFlow()
    private var session: AccountSession? = null

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.isLoading && _uiState.value.statuses.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val current = session ?: authRepository.restoreSession()
            if (current == null) {
                _uiState.value = HashtagTimelineUiState(isLoading = false, errorMessage = "ログインが必要です")
                return@launch
            }
            session = current
            timelineRepository.getHashtagTimeline(current, hashtag)
                .onSuccess { page ->
                    _uiState.value = HashtagTimelineUiState(
                        statuses = page.statuses,
                        isLoading = false,
                        nextMaxId = page.nextMaxId,
                        endReached = page.endReached,
                    )
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "投稿を取得できませんでした") }
                }
        }
    }

    fun loadMore() {
        val current = session ?: return
        val cursor = _uiState.value.nextMaxId ?: return
        if (_uiState.value.isLoadingMore || _uiState.value.endReached) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            timelineRepository.getHashtagTimeline(current, hashtag, maxId = cursor)
                .onSuccess { page ->
                    _uiState.update { state ->
                        state.copy(
                            statuses = (state.statuses + page.statuses).distinctBy(TimelineStatus::timelineId),
                            isLoadingMore = false,
                            nextMaxId = page.nextMaxId,
                            endReached = page.endReached || page.nextMaxId == state.nextMaxId,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoadingMore = false, errorMessage = error.message) }
                }
        }
    }

    fun toggleFavourite(status: TimelineStatus) = mutate(status) {
        timelineRepository.setFavourite(it, status.statusId, !status.favourited)
    }

    fun toggleReblog(status: TimelineStatus) = mutate(status) {
        timelineRepository.setReblogged(it, status.statusId, !status.reblogged)
    }

    fun setReaction(status: TimelineStatus, emoji: String?) = mutate(status) {
        timelineRepository.setFedibirdReaction(it, status.statusId, emoji)
    }

    private fun mutate(
        original: TimelineStatus,
        request: suspend (AccountSession) -> Result<TimelineStatus>,
    ) {
        val current = session ?: return
        viewModelScope.launch {
            request(current).onSuccess { updated ->
                _uiState.update { state ->
                    state.copy(statuses = state.statuses.map { if (it.statusId == original.statusId) updated else it })
                }
            }
        }
    }

    class Factory(
        private val hashtag: String,
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HashtagTimelineViewModel(hashtag, timelineRepository, authRepository) as T
    }
}
