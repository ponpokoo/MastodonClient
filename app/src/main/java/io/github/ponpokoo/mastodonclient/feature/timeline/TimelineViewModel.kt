package io.github.ponpokoo.mastodonclient.feature.timeline

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
import retrofit2.HttpException

data class TimelineUiState(
    val session: AccountSession? = null,
    val statuses: List<TimelineStatus> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val nextMaxId: String? = null,
    val errorMessage: String? = null,
    val requiresLogin: Boolean = false,
)

class TimelineViewModel(
    private val timelineRepository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        loadInitial()
    }

    fun refresh() {
        val session = _uiState.value.session ?: return
        if (_uiState.value.isRefreshing || _uiState.value.isInitialLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            timelineRepository.getHomeTimeline(session)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            statuses = page.statuses,
                            isRefreshing = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, refreshing = true) }
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        val session = state.session ?: return
        val cursor = state.nextMaxId ?: return
        if (state.isInitialLoading || state.isRefreshing || state.isLoadingMore || state.endReached) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            timelineRepository.getHomeTimeline(session, maxId = cursor)
                .onSuccess { page ->
                    _uiState.update { current ->
                        current.copy(
                            statuses = (current.statuses + page.statuses)
                                .distinctBy(TimelineStatus::timelineId),
                            isLoadingMore = false,
                            endReached = page.endReached || page.nextMaxId == current.nextMaxId,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, loadingMore = true) }
        }
    }

    fun retry() {
        if (_uiState.value.statuses.isEmpty()) loadInitial() else loadNextPage()
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = TimelineUiState(isInitialLoading = false, requiresLogin = true)
        }
    }

    private fun loadInitial() {
        if (_uiState.value.isInitialLoading && _uiState.value.session != null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isInitialLoading = true, errorMessage = null, requiresLogin = false)
            }
            val session = authRepository.restoreSession()
            if (session == null) {
                _uiState.value = TimelineUiState(isInitialLoading = false, requiresLogin = true)
                return@launch
            }
            _uiState.update { it.copy(session = session) }
            timelineRepository.getHomeTimeline(session)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            statuses = page.statuses,
                            isInitialLoading = false,
                            endReached = page.endReached,
                            nextMaxId = page.nextMaxId,
                        )
                    }
                }
                .onFailure { error -> showError(error, initial = true) }
        }
    }

    private fun showError(
        error: Throwable,
        initial: Boolean = false,
        refreshing: Boolean = false,
        loadingMore: Boolean = false,
    ) {
        val message = when ((error as? HttpException)?.code()) {
            401 -> "ログインの有効期限が切れました。ログインし直してください。"
            429 -> "アクセスが集中しています。しばらく待ってから再試行してください。"
            else -> error.message ?: "ホームタイムラインを取得できませんでした。"
        }
        _uiState.update {
            it.copy(
                isInitialLoading = if (initial) false else it.isInitialLoading,
                isRefreshing = if (refreshing) false else it.isRefreshing,
                isLoadingMore = if (loadingMore) false else it.isLoadingMore,
                errorMessage = message,
            )
        }
    }

    class Factory(
        private val timelineRepository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimelineViewModel(timelineRepository, authRepository) as T
    }
}
