package io.github.ponpokoo.mastodonclient.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SavedTimelinesUiState(
    val lists: List<MastodonList> = emptyList(),
    val statuses: List<TimelineStatus> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val nextMaxId: String? = null,
    val endReached: Boolean = false,
    val error: String? = null,
)

class SavedTimelinesViewModel(
    private val kind: SavedTimelineKind?,
    private val listId: String?,
    private val repository: TimelineRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SavedTimelinesUiState())
    val state = _state.asStateFlow()
    private var session: AccountSession? = null

    init { load() }
    fun retry() = load()

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val current = authRepository.restoreSession() ?: run {
            _state.value = SavedTimelinesUiState(loading = false, error = "ログインが必要です")
            return@launch
        }
        session = current
        if (kind == null) {
            repository.getLists(current).fold(
                { _state.value = SavedTimelinesUiState(lists = it, loading = false) },
                { _state.value = SavedTimelinesUiState(loading = false, error = it.message ?: "リストを取得できませんでした") },
            )
        } else {
            repository.getSavedTimeline(current, kind, listId).fold(
                { page -> _state.value = SavedTimelinesUiState(statuses = page.statuses, nextMaxId = page.nextMaxId, endReached = page.endReached, loading = false) },
                { _state.value = SavedTimelinesUiState(loading = false, error = it.message ?: "タイムラインを取得できませんでした") },
            )
        }
    }

    fun loadMore() {
        val current = session ?: return
        val selectedKind = kind ?: return
        val snapshot = _state.value
        val cursor = snapshot.nextMaxId ?: return
        if (snapshot.loadingMore || snapshot.endReached) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            repository.getSavedTimeline(current, selectedKind, listId, cursor).fold(
                { page -> _state.update { it.copy(
                    statuses = (it.statuses + page.statuses).distinctBy(TimelineStatus::statusId),
                    nextMaxId = page.nextMaxId,
                    endReached = page.endReached,
                    loadingMore = false,
                ) } },
                { error -> _state.update { it.copy(loadingMore = false, error = error.message ?: "続きを取得できませんでした") } },
            )
        }
    }

    class Factory(
        private val kind: SavedTimelineKind?,
        private val listId: String?,
        private val repository: TimelineRepository,
        private val authRepository: AuthRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SavedTimelinesViewModel(kind, listId, repository, authRepository) as T
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SavedTimelinesScreen(
    title: String,
    viewModel: SavedTimelinesViewModel,
    preferences: AppPreferences,
    showLists: Boolean,
    onBack: () -> Unit,
    onListClick: (MastodonList) -> Unit,
    onStatusClick: (String) -> Unit,
    onAccountClick: (String) -> Unit,
    onMediaClick: (List<MediaAttachment>, Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    Scaffold(
        modifier = Modifier.testTag("saved_timelines_screen"),
        topBar = { TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る") } },
        ) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.statuses.isEmpty() && state.lists.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(state.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::retry) { Text("再試行") } }
            showLists -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (state.lists.isEmpty()) item { Text("リストがありません", Modifier.padding(24.dp)) }
                items(state.lists, key = MastodonList::id) { list ->
                    Text(list.title, Modifier.fillMaxWidth().clickable { onListClick(list) }.padding(20.dp), style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (state.statuses.isEmpty()) item { Text("投稿がありません", Modifier.padding(24.dp)) }
                items(state.statuses, key = TimelineStatus::timelineId) { status ->
                    StatusCard(
                        status = status,
                        onStatusClick = onStatusClick,
                        onAuthorClick = onAccountClick,
                        onMediaClick = onMediaClick,
                        onOpenLink = onOpenLink,
                        onUnavailableAction = {},
                        displayPreferences = preferences.timelineDisplay,
                        gifAutoplay = preferences.gifAutoplay,
                        videoAutoplay = preferences.videoAutoplay,
                    )
                    HorizontalDivider()
                }
                if (state.loadingMore) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (!state.endReached) item { TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("さらに読み込む") } }
            }
        }
    }
}
