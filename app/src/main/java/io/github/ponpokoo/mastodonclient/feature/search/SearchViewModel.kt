package io.github.ponpokoo.mastodonclient.feature.search

import io.github.ponpokoo.mastodonclient.feature.common.moderated
import io.github.ponpokoo.mastodonclient.feature.common.withModeration
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.SearchException
import io.github.ponpokoo.mastodonclient.domain.model.SearchFailure
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed
import io.github.ponpokoo.mastodonclient.domain.model.ExplorePage
import io.github.ponpokoo.mastodonclient.domain.model.SearchTag
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

data class SearchTabState(
    val results: SearchResults? = null,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextOffset: Int? = null,
    val endReached: Boolean = false,
    val error: SearchFailure? = null,
    val errorIsPagination: Boolean = false,
)

data class SearchUiState(
    val searchQuery: String = "",
    val selectedTarget: SearchTarget = SearchTarget.Posts,
    val isSearchActive: Boolean = false,
    val submittedQuery: String? = null,
    val tabs: Map<SearchTarget, SearchTabState> = emptyMap(),
    val sessionKey: String? = null,
) {
    val selectedTab: SearchTabState get() = tabs[selectedTarget] ?: SearchTabState()
    val searchResults: SearchResults? get() = selectedTab.results
    val isSearching: Boolean get() = selectedTab.isSearching
    val searchError: String? get() = selectedTab.error?.message()
}

internal fun SearchFailure.message(): String = when (this) {
    SearchFailure.Timeout -> "検索に時間がかかっています。時間内に結果を取得できませんでした。"
    SearchFailure.Connection -> "サーバーに接続できませんでした。通信状態を確認してください。"
    SearchFailure.Authentication -> "検索する権限を確認できませんでした。アカウントの再認証をお試しください。"
    SearchFailure.RateLimited -> "検索回数の上限に達しました。少し待ってからお試しください。"
    SearchFailure.Server -> "サーバーで問題が発生しました。少し待ってからお試しください。"
    SearchFailure.Rejected -> "サーバーが検索を受け付けませんでした。検索語やサーバーの対応状況を確認してください。"
    SearchFailure.Unknown -> "検索できませんでした。もう一度お試しください。"
}

class SearchViewModel(private val repository: TimelineRepository, browsing: BrowsingSession) : SessionScopedViewModel(browsing) {
    private val mutableState = MutableStateFlow(SearchUiState())
    val uiState = mutableState.moderated(viewModelScope, repository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private val mutableExplore = MutableStateFlow(ExploreUiState())
    val exploreState = mutableExplore.moderated(viewModelScope, repository, { browsing.snapshot.value.account }) { state, moderation, account ->
        state.withModeration(moderation, account)
    }
    private val exploreJobs = mutableMapOf<ExploreFeed, Job>()
    private val exploreGenerations = mutableMapOf<ExploreFeed, Long>()
    private data class TagOverride(val tag: SearchTag, val revision: Long)
    private val tagOverrides = mutableMapOf<String, TagOverride>()
    private var tagRevision = 0L
    private var searchJob: Job? = null
    private var requestGeneration = 0L

    init { observeSession() }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        requestGeneration++
        searchJob = null
        mutableState.value = SearchUiState(sessionKey = snapshot.account?.let { "${it.sessionId}:${snapshot.generation}" })
        exploreJobs.clear()
        exploreGenerations.clear()
        tagOverrides.clear()
        tagRevision = 0L
        mutableExplore.value = ExploreUiState(sessionKey = mutableState.value.sessionKey)
    }

    fun ensureExploreLoaded() {
        val feed = exploreState.value.selectedFeed
        if (exploreState.value.tabs[feed] == null) requestExplore(feed)
    }

    fun selectExploreFeed(feed: ExploreFeed) {
        mutableExplore.update { it.copy(selectedFeed = feed) }
        ensureExploreLoaded()
    }

    fun refreshExplore() = requestExplore(exploreState.value.selectedFeed)

    fun loadMoreExplore() {
        val feed = exploreState.value.selectedFeed
        val tab = exploreState.value.tabs[feed] ?: return
        if (!tab.loading && !tab.loadingMore && tab.page?.nextCursor != null) requestExplore(feed, append = true)
    }

    fun retryExplore() {
        if (exploreState.value.tabs[exploreState.value.selectedFeed]?.errorIsPagination == true) loadMoreExplore()
        else refreshExplore()
    }

    fun setTagFollowing(tag: SearchTag, following: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val name = tag.name.lowercase(java.util.Locale.ROOT)
        if (name in exploreState.value.busyTags) return
        mutableExplore.update { it.copy(busyTags = it.busyTags + name, actionMessage = null) }
        requestScope.launch {
            repository.setTagFollowing(snapshot.account!!, tag.name, following).forSession(snapshot).fold(
                onSuccess = { updated -> browsing.publish(snapshot, BrowsingSession.Change.TagUpdated(updated)) },
                onFailure = { error -> mutableExplore.update { it.copy(actionMessage = error.exploreMessage()) } },
            )
            mutableExplore.update { it.copy(busyTags = it.busyTags - name) }
        }
    }

    fun consumeExploreMessage() { mutableExplore.update { it.copy(actionMessage = null) } }

    private fun requestExplore(feed: ExploreFeed, append: Boolean = false) {
        val snapshot = currentSnapshot() ?: return
        val before = exploreState.value.tabs[feed] ?: ExploreTabState()
        val cursor = if (append) before.page?.nextCursor ?: return else null
        exploreJobs.remove(feed)?.cancel()
        val generation = (exploreGenerations[feed] ?: 0) + 1
        val initialTagRevision = tagRevision
        exploreGenerations[feed] = generation
        updateExploreTab(feed) { it.copy(loading = !append, loadingMore = append, error = null, errorIsPagination = false) }
        exploreJobs[feed] = requestScope.launch {
            repository.getExplore(snapshot.account!!, feed, cursor, 20).forSession(snapshot).let { response ->
                if (exploreGenerations[feed] != generation) return@launch
                response.fold(onSuccess = { page ->
                    val old = if (append) exploreState.value.tabs[feed]?.page else null
                    val merged = old?.let { ExplorePage(
                        (it.statuses + page.statuses).distinctBy { status -> status.timelineId },
                        (it.tags + page.tags).distinctBy { tag -> tag.name.lowercase(java.util.Locale.ROOT) },
                        (it.news + page.news).distinctBy { news -> news.url }, page.nextCursor,
                    ) } ?: page
                    val stalled = old != null && (merged.count() == old.count() || page.nextCursor == cursor)
                    // A refresh adopts server state, except changes confirmed while it was in flight.
                    val overrides = tagOverrides.filterValues { feed != ExploreFeed.Followed || append || it.revision > initialTagRevision }
                    val confirmedAdds = if (feed == ExploreFeed.Followed) overrides.values.map { it.tag }.filter { it.following == true } else emptyList()
                    val tags = (confirmedAdds + merged.tags).distinctBy { it.name.lowercase(java.util.Locale.ROOT) }.map { tag ->
                        overrides[tag.name.lowercase(java.util.Locale.ROOT)]?.let { tag.copy(following = it.tag.following) } ?: tag
                    }
                        .filter { feed != ExploreFeed.Followed || it.following != false }
                    if (feed == ExploreFeed.Followed && !append) tagOverrides.entries.removeAll { it.value.revision <= initialTagRevision }
                    updateExploreTab(feed) { ExploreTabState(page = merged.copy(tags = tags, nextCursor = if (stalled) null else page.nextCursor)) }
                }, onFailure = { error -> updateExploreTab(feed) { it.copy(loading = false, loadingMore = false,
                    error = error.exploreMessage(), errorIsPagination = append) } })
            }
        }
    }

    private fun updateExploreTab(feed: ExploreFeed, update: (ExploreTabState) -> ExploreTabState) {
        mutableExplore.update { it.copy(tabs = it.tabs + (feed to update(it.tabs[feed] ?: ExploreTabState()))) }
    }

    private fun applyTagUpdate(tag: SearchTag) {
        val name = tag.name.lowercase(java.util.Locale.ROOT)
        tagOverrides[name] = TagOverride(tag, ++tagRevision)
        mutableExplore.update { state -> state.copy(tabs = state.tabs.mapValues { (feed, tab) ->
            tab.copy(page = tab.page?.let { page ->
                val tags = page.tags.map { if (it.name.equals(tag.name, ignoreCase = true)) tag else it }
                page.copy(tags = if (feed != ExploreFeed.Followed) tags else if (tag.following == true)
                    (listOf(tag) + tags).distinctBy { it.name.lowercase(java.util.Locale.ROOT) }
                    else tags.filterNot { it.name.equals(tag.name, ignoreCase = true) })
            })
        }) }
        mutableState.update { state -> state.copy(tabs = state.tabs.mapValues { (_, tab) -> tab.copy(
            results = tab.results?.copy(hashtags = tab.results.hashtags.map { if (it.name.equals(tag.name, ignoreCase = true)) tag else it }),
        ) }) }
    }

    fun enterSearch() { mutableState.update { it.copy(isSearchActive = true) } }

    fun returnToExplore(clear: Boolean = false) {
        cancelRequest()
        mutableState.update {
            if (clear) SearchUiState(sessionKey = it.sessionKey, selectedTarget = it.selectedTarget)
            else it.copy(isSearchActive = false)
        }
    }

    fun onQueryChanged(value: String) {
        if (value == uiState.value.searchQuery) return
        cancelRequest()
        mutableState.update { it.copy(searchQuery = value, isSearchActive = true, submittedQuery = null, tabs = emptyMap()) }
    }

    fun selectTarget(target: SearchTarget) {
        if (target == uiState.value.selectedTarget) return
        cancelRequest()
        mutableState.update { it.copy(selectedTarget = target) }
        val state = uiState.value
        if (state.isSearchActive && state.submittedQuery == state.searchQuery.trim() && state.selectedTab.results == null) {
            requestPage(append = false)
        }
    }

    fun search() {
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) return
        mutableState.update { it.copy(isSearchActive = true, submittedQuery = query) }
        requestPage(append = false)
    }

    fun loadMore() {
        val state = uiState.value
        val tab = state.selectedTab
        if (!state.isSearchActive || tab.results == null || tab.isSearching || tab.isLoadingMore ||
            tab.endReached || tab.nextOffset == null || state.submittedQuery != state.searchQuery.trim()) return
        requestPage(append = true)
    }

    fun retry() {
        if (uiState.value.selectedTab.errorIsPagination) loadMore() else search()
    }

    private fun cancelRequest() {
        requestGeneration++
        searchJob?.cancel()
        searchJob = null
        mutableState.update { state -> state.copy(tabs = state.tabs.mapValues { (_, tab) ->
            tab.copy(isSearching = false, isLoadingMore = false)
        }) }
    }

    private fun requestPage(append: Boolean) {
        val snapshot = currentSnapshot() ?: return
        val query = uiState.value.searchQuery.trim()
        if (query.isEmpty()) return
        cancelRequest()
        val generation = requestGeneration
        val target = uiState.value.selectedTarget
        val before = uiState.value.selectedTab
        val offset = if (append) before.nextOffset ?: return else 0
        updateTab(target) { it.copy(isSearching = !append, isLoadingMore = append, error = null, errorIsPagination = false) }
        searchJob = requestScope.launch {
            val response = repository.search(snapshot.account!!, query, target, offset, limit = 20).forSession(snapshot)
            if (generation != requestGeneration) return@launch
            response.fold(
                onSuccess = { page ->
                    val old = if (append) uiState.value.tabs[target]?.results else null
                    val results = old?.merge(page.results) ?: page.results
                    val noProgress = old != null && results.count(target) == old.count(target)
                    updateTab(target) { SearchTabState(
                        results = results,
                        nextOffset = if (noProgress) null else page.nextOffset,
                        endReached = page.endReached || noProgress,
                    ) }
                },
                onFailure = { error -> updateTab(target) { it.copy(
                    isSearching = false, isLoadingMore = false,
                    error = (error as? SearchException)?.failure ?: SearchFailure.Unknown,
                    errorIsPagination = append,
                ) } },
            )
        }
    }

    private fun updateTab(target: SearchTarget, update: (SearchTabState) -> SearchTabState) {
        mutableState.update { it.copy(tabs = it.tabs + (target to update(it.tabs[target] ?: SearchTabState()))) }
    }

    override fun onChange(change: BrowsingSession.Change) {
        if (change is BrowsingSession.Change.TagUpdated) applyTagUpdate(change.tag)
        val deleted = when (change) {
            is BrowsingSession.Change.StatusDeleted -> change.statusId
            is BrowsingSession.Change.Stream -> (change.event as? TimelineStreamEvent.StatusDeleted)?.statusId
            else -> null
        }
        mutableState.update { state -> state.copy(tabs = state.tabs.mapValues { (_, tab) ->
            tab.copy(results = tab.results?.let { results -> results.copy(statuses = results.statuses.filterNot { it.statusId == deleted }.map { status ->
                if (change is BrowsingSession.Change.StatusUpdated) status.withUpdatedActions(change.status) else status
            }) })
        }) }
        mutableExplore.update { state -> state.copy(tabs = state.tabs.mapValues { (_, tab) -> tab.copy(
            page = tab.page?.let { page -> page.copy(statuses = page.statuses.filterNot { it.statusId == deleted }.map {
                if (change is BrowsingSession.Change.StatusUpdated) it.withUpdatedActions(change.status) else it
            }) },
        ) }) }
    }
}

private fun SearchResults.merge(next: SearchResults) = SearchResults(
    accounts = (accounts + next.accounts).distinctBy { it.id },
    statuses = (statuses + next.statuses).distinctBy { it.timelineId },
    hashtags = (hashtags + next.hashtags).distinctBy { it.url },
)
