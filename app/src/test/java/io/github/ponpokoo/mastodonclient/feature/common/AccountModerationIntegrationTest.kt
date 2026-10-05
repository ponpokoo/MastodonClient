package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountModerationIntegrationTest : ScreenViewModelTestBase() {
    private val target = testStatus("target").copy(author = testStatus().author.copy(id = "target-author"))
    private val kept = testStatus("kept")
    private val rows get() = listOf(target, target.copy(timelineId = "boost-of-target", boostedBy = kept.author),
        kept.copy(timelineId = "boost-by-target", boostedBy = target.author), kept)
    private val notices get() = listOf(testNotification("actor").copy(account = target.author, status = null),
        testNotification("post").copy(status = target), testNotification("kept").copy(status = kept))

    private inner class Repository : ScreenRepositoryFake() {
        override val moderation = MutableStateFlow(AccountModerationState())
        var fail = false
        override suspend fun getRelationship(session: AccountSession, accountId: String) =
            Result.success(moderation.value.relationship(session, accountId) ?: AccountRelationship())
        override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> {
            if (fail) return Result.failure(IllegalStateException("offline"))
            val rel = (moderation.value.relationship(session, accountId) ?: AccountRelationship()).copy(blocking = blocked)
            moderation.value = moderation.value.changed(session, accountId, rel)
            return Result.success(rel)
        }
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(rows, null, true))
        override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) = Result.success(NotificationPage(notices, null, true))
        override suspend fun getProfile(session: AccountSession, accountId: String) =
            Result.success(testProfile().copy(author = target.author, statuses = rows, pinnedStatuses = listOf(target), isOwnProfile = false))
        override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
            Result.success(TimelinePage(rows, null, true))
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(if (statusId == "target") target else kept, listOf(target), listOf(target, kept)))
        override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
            Result.success(SearchPage(SearchResults(emptyList(), rows, emptyList()), null, true))
        override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) =
            Result.success(ExplorePage(statuses = rows))
    }
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = testAccount
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
    }

    @Test fun blockRemovesEveryReferenceAndLateRefreshAndStreamingCannotRestoreIt() = runTest(dispatcher) {
        val repository = Repository()
        val browsing = BrowsingSession().also { it.activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val notifications = own(NotificationsViewModel(repository, browsing))
        val search = own(SearchViewModel(repository, browsing))
        val profile = own(AccountProfileViewModel("target-author", repository, auth))
        val detail = own(StatusDetailViewModel("kept", repository, auth))
        val targetDetail = own(StatusDetailViewModel("target", repository, auth))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        notifications.loadNotifications()
        search.onQueryChanged("query"); search.search(); search.ensureExploreLoaded()
        actions.loadModerationMenu(target.author.id)
        advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
        actions.block(target)
        advanceUntilIdle()
        assertEquals(listOf("kept"), timeline.uiState.value.statuses.map { it.timelineId })
        assertEquals(listOf("kept"), notifications.uiState.value.notifications.map { it.id })
        assertEquals(listOf("kept"), search.uiState.value.searchResults!!.statuses.map { it.timelineId })
        assertEquals(listOf("kept"), search.exploreState.value.tabs.getValue(ExploreFeed.Posts).page!!.statuses.map { it.timelineId })
        assertEquals(listOf("kept"), profile.uiState.value.profile!!.statuses.map { it.timelineId })
        assertTrue(profile.uiState.value.profile!!.pinnedStatuses.isEmpty())
        assertTrue(detail.uiState.value.detail!!.ancestors.isEmpty())
        assertNull(targetDetail.uiState.value.detail)
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(target)))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(notices.first())))
        timeline.refresh(); notifications.loadNotifications(force = true)
        advanceUntilIdle()
        assertEquals(listOf("kept"), timeline.uiState.value.statuses.map { it.timelineId })
        assertEquals(listOf("kept"), notifications.uiState.value.notifications.map { it.id })
        actions.loadModerationMenu(target.author.id); advanceUntilIdle()
        actions.block(target, false); advanceUntilIdle()
        assertEquals(listOf("kept"), timeline.uiState.value.statuses.map { it.timelineId })
        timeline.refresh(); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
    }

    @Test fun failedBlockKeepsRowsAndAnotherLoginWithTheSameAuthorIsIndependent() = runTest(dispatcher) {
        val repository = Repository()
        val browsing = BrowsingSession().also { it.activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing))
        val actions = own(StatusActionsViewModel(repository, browsing))
        advanceUntilIdle()
        actions.loadModerationMenu(target.author.id); advanceUntilIdle()
        repository.fail = true
        actions.block(target); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
        assertTrue(repository.moderation.value.relationships.isEmpty())
        repository.fail = false
        actions.block(target); advanceUntilIdle()
        assertEquals(1, timeline.uiState.value.statuses.size)
        browsing.activate(secondAccount); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
        browsing.activate(testAccount); advanceUntilIdle()
        assertEquals(1, timeline.uiState.value.statuses.size)
    }
}
