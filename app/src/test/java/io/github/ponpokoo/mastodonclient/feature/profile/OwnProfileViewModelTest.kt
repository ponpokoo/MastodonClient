package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnProfileViewModelTest : ScreenViewModelTestBase() {
    @Test fun postsAndPinsAppearTogetherRegardlessOfWhichRequestFinishesFirst() = runTest(dispatcher) {
        for (pinsFirst in listOf(false, true)) {
            val repository = ProfileLoadingRepositoryFake()
            val browsing = BrowsingSession().apply { activate(testAccount) }
            val viewModel = own(OwnProfileViewModel(repository, browsing))
            advanceUntilIdle()
            viewModel.loadProfile()
            advanceUntilIdle()
            assertTrue(repository.postsRequested && repository.pinsRequested)
            val page = Result.success(TimelinePage(listOf(testStatus("normal")), "next", false))
            val pins = Result.success(listOf(testStatus("pinned")))
            if (pinsFirst) repository.pinned.complete(pins) else repository.posts.complete(page)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
            assertTrue(viewModel.uiState.value.profile!!.pinnedStatuses.isEmpty())
            assertTrue(viewModel.uiState.value.isLoadingMoreProfile)
            viewModel.loadMoreProfile()
            if (pinsFirst) repository.posts.complete(page) else repository.pinned.complete(pins)
            advanceUntilIdle()
            assertEquals(listOf("normal"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
            assertEquals(listOf("pinned"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
            assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
        }
    }

    @Test fun pinFailureShowsPostsAndKeepsPreviousPinsOnRefresh() = runTest(dispatcher) {
        val repository = ProfileLoadingRepositoryFake().apply {
            posts.complete(Result.success(TimelinePage(listOf(testStatus()), null, true)))
            pinned.complete(Result.failure(IllegalStateException("pins unavailable")))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        assertEquals(listOf("post"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertTrue(viewModel.uiState.value.profile!!.pinnedStatuses.isEmpty())
        assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
        repository.pinned = CompletableDeferred(Result.success(listOf(testStatus("pinned"))))
        viewModel.refreshProfile()
        advanceUntilIdle()
        repository.posts = CompletableDeferred(Result.success(TimelinePage(listOf(testStatus("fresh")), null, true)))
        repository.pinned = CompletableDeferred(Result.failure(IllegalStateException("pins unavailable")))
        viewModel.refreshProfile()
        advanceUntilIdle()
        assertEquals(listOf("fresh"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals(listOf("pinned"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
        assertFalse(viewModel.uiState.value.isRefreshingProfile)
    }

    @Test fun sessionChangeRejectsPostsWaitingForAnOldPinRequest() = runTest(dispatcher) {
        val repository = object : ProfileLoadingRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, maxId: String?) = if (session == testAccount) {
                super.getProfileStatuses(session, accountId, tab, maxId)
            } else Result.success(TimelinePage(listOf(testStatus("new post")), null, true))
            override suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String) =
                if (session == testAccount) withContext(NonCancellable) {
                    super.getPinnedProfileStatuses(session, accountId)
                } else Result.success(listOf(testStatus("new pin")))
        }
        repository.posts.complete(Result.success(TimelinePage(listOf(testStatus("old post")), null, true)))
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        repository.pinned.complete(Result.success(listOf(testStatus("old pin"))))
        advanceUntilIdle()
        assertEquals(listOf("new post"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals(listOf("new pin"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
    }

    @Test fun cachedTabsReflectStatusUpdatesAndDeletion() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?) = Result.success(TimelinePage(listOf(testStatus()), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.prepareProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusUpdated(testStatus().copy(favourited = true)))
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        assertTrue(viewModel.uiState.value.profile!!.statuses.single().favourited)
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.StatusDeleted("post"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profileTabs.values.all { it.statuses.isEmpty() })
    }

    @Test fun preparingTabStartsBeforeSelectionAndDoesNotRefetchOnReturn() = runTest(dispatcher) {
        val replies = CompletableDeferred<Result<TimelinePage>>()
        val calls = mutableListOf<ProfileStatusTab>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?): Result<TimelinePage> {
                calls += tab
                return if (tab == ProfileStatusTab.Replies) replies.await()
                    else super.getProfileStatuses(session, accountId, tab, maxId)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.prepareProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Posts, viewModel.uiState.value.profileSelectedTab)
        assertTrue(viewModel.uiState.value.profileTabs.getValue(ProfileStatusTab.Replies).isLoading)
        viewModel.prepareProfileTab(ProfileStatusTab.Replies)
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        replies.complete(Result.success(TimelinePage(listOf(testStatus("reply")), null, true)))
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Posts)
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(listOf(ProfileStatusTab.Posts, ProfileStatusTab.Replies), calls)
        assertEquals(listOf("reply"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertFalse(viewModel.uiState.value.isLoadingProfile)
    }

    @Test fun tabCacheRetainsPaginationAndRefreshReplacesOnlySelectedTab() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<ProfileStatusTab, String?>>()
        var refreshed = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?): Result<TimelinePage> {
                calls += tab to maxId
                return Result.success(when {
                    tab == ProfileStatusTab.Replies -> TimelinePage(listOf(testStatus("reply")), null, true)
                    maxId != null -> TimelinePage(listOf(testStatus("older")), "older-cursor", false)
                    refreshed -> TimelinePage(listOf(testStatus("fresh")), null, true)
                    else -> TimelinePage(listOf(testStatus()), "next", false)
                })
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.loadMoreProfile()
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Posts)
        assertEquals(listOf("post", "older"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals("older-cursor", viewModel.uiState.value.profile!!.nextMaxId)
        refreshed = true
        viewModel.refreshProfile()
        advanceUntilIdle()
        assertEquals(listOf("fresh"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(listOf("reply"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals(4, calls.size)
    }

    @Test fun sessionChangeDiscardsAllCachedTabs() = runTest(dispatcher) {
        val oldReplies = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?) = if (session == testAccount && tab == ProfileStatusTab.Replies) {
                    withContext(NonCancellable) { oldReplies.await() }
                } else Result.success(TimelinePage(listOf(testStatus("${session.sessionId}-${tab.name}")), null, true))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.prepareProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        oldReplies.complete(Result.success(TimelinePage(listOf(testStatus("old reply")), null, true)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.profileTabs.containsKey(ProfileStatusTab.Replies))
        assertEquals(listOf("two-Posts"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
    }

    @Test fun profileHeaderAppearsBeforeStatusesFinishLoading() = runTest(dispatcher) {
        val delayedStatuses = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) =
                Result.success(testProfile().copy(statuses = emptyList(), pinnedStatuses = emptyList()))
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
                delayedStatuses.await()
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.profile)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertTrue(viewModel.uiState.value.isLoadingMoreProfile)
        delayedStatuses.complete(Result.success(TimelinePage(listOf(testStatus()), null, true)))
        advanceUntilIdle()
        assertEquals(listOf("post"), viewModel.uiState.value.profile?.statuses?.map { it.statusId })
        assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
    }

    @Test fun latestTabWinsAndOldPaginationCannotAppendToIt() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<TimelinePage>>()
        val replies = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> = when {
                maxId != null -> withContext(NonCancellable) { oldPage.await() }
                tab == ProfileStatusTab.Replies -> withContext(NonCancellable) { replies.await() }
                else -> super.getProfileStatuses(session, accountId, tab, maxId)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        viewModel.loadMoreProfile()
        advanceUntilIdle()
        viewModel.selectProfileTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoadingProfile)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertNull(viewModel.uiState.value.profile!!.nextMaxId)
        viewModel.selectProfileTab(ProfileStatusTab.Media)
        advanceUntilIdle()
        oldPage.complete(Result.success(TimelinePage(listOf(testStatus("old page")), null, true)))
        replies.complete(Result.failure(IllegalStateException("old tab")))
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Media, viewModel.uiState.value.profileSelectedTab)
        assertEquals(listOf("Media"), viewModel.uiState.value.profile?.statuses?.map { it.statusId })
        assertFalse(viewModel.uiState.value.isLoadingMoreProfile)
        assertNull(viewModel.uiState.value.profileError)
    }

    @Test fun requestedProfileReloadsForNewAccountAndRejectsOldProfile() = runTest(dispatcher) {
        val delayed = CompletableDeferred<Result<UserProfile>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfile(session: AccountSession, accountId: String) =
                if (session == testAccount) withContext(NonCancellable) { delayed.await() }
                else Result.success(testProfile().copy(noteHtml = "second"))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val viewModel = own(OwnProfileViewModel(repository, browsing))
        advanceUntilIdle()
        viewModel.loadProfile()
        advanceUntilIdle()
        browsing.activate(secondAccount)
        advanceUntilIdle()
        delayed.complete(Result.success(testProfile().copy(noteHtml = "old")))
        advanceUntilIdle()
        assertEquals("second", viewModel.uiState.value.profile?.noteHtml)
    }
}
