package io.github.ponpokoo.mastodonclient.feature.search

import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStreamEvent
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.domain.session.withUpdatedActions
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val searchQuery: String = "",
    val searchResults: SearchResults? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
)

class SearchViewModel(private val repository: TimelineRepository, browsing: BrowsingSession) : SessionScopedViewModel(browsing) {
    private val mutableState = MutableStateFlow(SearchUiState())
    val uiState = mutableState.asStateFlow()
    private var searchJob: Job? = null

    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) { mutableState.value = SearchUiState() }

    fun onQueryChanged(value: String) {
        searchJob?.cancel()
        mutableState.update { it.copy(searchQuery = value, searchResults = null, isSearching = false, searchError = null) }
    }

    fun search() {
        val snapshot = currentSnapshot() ?: return
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        mutableState.update { it.copy(isSearching = true, searchError = null) }
        searchJob = requestScope.launch {
            repository.search(snapshot.account!!, query).forSession(snapshot).fold(
                onSuccess = { results -> mutableState.update { it.copy(searchResults = results, isSearching = false) } },
                onFailure = { error -> mutableState.update { it.copy(isSearching = false, searchError = error.message ?: "検索できませんでした") } },
            )
        }
    }

    override fun onChange(change: BrowsingSession.Change) {
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        mutableState.update { state -> state.copy(searchResults = state.searchResults?.let { results ->
            results.copy(statuses = results.statuses.filterNot { it.statusId == deleted }.map { status ->
                if (change is BrowsingSession.Change.StatusUpdated) status.withUpdatedActions(change.status) else status
            })
        }) }
    }
}
