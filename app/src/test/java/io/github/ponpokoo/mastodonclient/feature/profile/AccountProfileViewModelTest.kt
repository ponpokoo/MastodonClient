package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.AccountModerationState
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.feature.common.ScreenRepositoryFake
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelTestBase
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import io.github.ponpokoo.mastodonclient.feature.common.testProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountProfileViewModelTest : ScreenViewModelTestBase() {
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = testAccount
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun logout() = Unit
    }

    @Test fun postsAndPinsAppearTogetherRegardlessOfWhichRequestFinishesFirst() = runTest(dispatcher) {
        for (pinsFirst in listOf(false, true)) {
            val repository = ProfileLoadingRepositoryFake()
            val viewModel = own(AccountProfileViewModel("author", repository, auth))
            advanceUntilIdle()
            assertTrue(repository.postsRequested && repository.pinsRequested)
            val page = Result.success(TimelinePage(listOf(testStatus("normal")), "next", false))
            val pins = Result.success(listOf(testStatus("pinned")))
            if (pinsFirst) repository.pinned.complete(pins) else repository.posts.complete(page)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
            assertTrue(viewModel.uiState.value.profile!!.pinnedStatuses.isEmpty())
            assertTrue(viewModel.uiState.value.isLoadingMore)
            viewModel.loadMore()
            if (pinsFirst) repository.posts.complete(page) else repository.pinned.complete(pins)
            advanceUntilIdle()
            assertEquals(listOf("normal"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
            assertEquals(listOf("pinned"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
            assertFalse(viewModel.uiState.value.isLoadingMore)
        }
    }

    @Test fun pinFailureShowsPostsAndKeepsPreviousPinsOnRefresh() = runTest(dispatcher) {
        val repository = ProfileLoadingRepositoryFake().apply {
            posts.complete(Result.success(TimelinePage(listOf(testStatus()), null, true)))
            pinned.complete(Result.failure(IllegalStateException("pins unavailable")))
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        assertEquals(listOf("post"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertTrue(viewModel.uiState.value.profile!!.pinnedStatuses.isEmpty())
        assertFalse(viewModel.uiState.value.isLoadingMore)
        repository.pinned = CompletableDeferred(Result.success(listOf(testStatus("pinned"))))
        viewModel.refresh()
        advanceUntilIdle()
        repository.posts = CompletableDeferred(Result.success(TimelinePage(listOf(testStatus("fresh")), null, true)))
        repository.pinned = CompletableDeferred(Result.failure(IllegalStateException("pins unavailable")))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(listOf("fresh"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals(listOf("pinned"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test fun refreshRejectsPostsWaitingForAnOlderPinRequest() = runTest(dispatcher) {
        val repository = object : ProfileLoadingRepositoryFake() {
            override suspend fun getPinnedProfileStatuses(session: AccountSession, accountId: String) =
                withContext(NonCancellable) { super.getPinnedProfileStatuses(session, accountId) }
        }
        repository.posts.complete(Result.success(TimelinePage(listOf(testStatus("old post")), null, true)))
        val oldPins = repository.pinned
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        repository.posts = CompletableDeferred(Result.success(TimelinePage(listOf(testStatus("fresh post")), null, true)))
        repository.pinned = CompletableDeferred(Result.success(listOf(testStatus("fresh pin"))))
        viewModel.refresh()
        advanceUntilIdle()
        oldPins.complete(Result.success(listOf(testStatus("old pin"))))
        advanceUntilIdle()
        assertEquals(listOf("fresh post"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals(listOf("fresh pin"), viewModel.uiState.value.profile!!.pinnedStatuses.map { it.statusId })
    }

    @Test fun muteStopsPendingPaginationAndTabsUntilUnmuteAndRefresh() = runTest(dispatcher) {
        val older = CompletableDeferred<Result<TimelinePage>>()
        val calls = mutableListOf<Pair<ProfileStatusTab, String?>>()
        var lookups = 0
        val repository = object : ScreenRepositoryFake() {
            override val moderation = MutableStateFlow(AccountModerationState())
            override suspend fun getProfile(session: AccountSession, accountId: String) =
                Result.success(testProfile().copy(author = testStatus().author.copy(id = accountId), isOwnProfile = false))
            override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> {
                lookups++
                return Result.success(moderation.value.relationship(session, accountId) ?: AccountRelationship())
            }
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?): Result<TimelinePage> {
                calls += tab to maxId
                return if (maxId != null) older.await() else Result.success(TimelinePage(
                    listOf(testStatus().copy(author = testStatus().author.copy(id = accountId))), "older", false))
            }
            override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
                val relationship = AccountRelationship(muting = muted)
                moderation.value = moderation.value.changed(session, accountId, relationship)
                return Result.success(relationship)
            }
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        viewModel.loadModerationMenu(); advanceUntilIdle()
        assertEquals(1, lookups)
        viewModel.loadMore(); runCurrent()
        assertTrue(viewModel.uiState.value.isLoadingMore)
        viewModel.setProfileMuted(true); advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertNull(viewModel.uiState.value.profile!!.nextMaxId)
        assertTrue(viewModel.uiState.value.profile!!.endReached)
        assertTrue(viewModel.uiState.value.profileTabs.values.all { it.isLoaded && !it.isLoading && !it.isLoadingMore && !it.isRefreshing })
        assertFalse(viewModel.uiState.value.isLoadingMore)
        viewModel.loadMore()
        viewModel.selectTab(ProfileStatusTab.Replies)
        viewModel.refresh(); advanceUntilIdle()
        assertEquals(2, calls.size)
        older.complete(Result.success(TimelinePage(listOf(testStatus("late")), "next", false)))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        viewModel.loadModerationMenu(); advanceUntilIdle()
        viewModel.setProfileMuted(false); advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        viewModel.refresh(); advanceUntilIdle()
        assertEquals(3, calls.size)
        assertEquals(1, viewModel.uiState.value.profile!!.statuses.size)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test fun cachedTabsReflectFavouriteAndDeletion() = runTest(dispatcher) {
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?) = Result.success(TimelinePage(listOf(testStatus()), null, true))
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        viewModel.prepareTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        viewModel.toggleFavourite(testStatus())
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Replies)
        assertTrue(viewModel.uiState.value.profile!!.statuses.single().favourited)
        viewModel.deleteStatus(testStatus())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.profileTabs.values.all { it.statuses.isEmpty() })
    }

    @Test fun preparingTabAndReturningToItKeepsItsPostsAndCursor() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<ProfileStatusTab, String?>>()
        var failRefresh = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab,
                maxId: String?): Result<TimelinePage> {
                calls += tab to maxId
                if (failRefresh && tab == ProfileStatusTab.Replies) return Result.failure(IllegalStateException("refresh failed"))
                return Result.success(TimelinePage(listOf(testStatus(if (maxId == null) tab.name else "older")),
                    if (maxId == null) "next" else "older-cursor", false))
            }
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        viewModel.prepareTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Posts, viewModel.uiState.value.selectedTab)
        viewModel.selectTab(ProfileStatusTab.Replies)
        viewModel.loadMore()
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Posts)
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(3, calls.size)
        assertEquals(listOf("Replies", "older"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertEquals("older-cursor", viewModel.uiState.value.profile!!.nextMaxId)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(4, calls.size)
        assertEquals(listOf("Replies"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertTrue(viewModel.uiState.value.profileTabs.getValue(ProfileStatusTab.Posts).isLoaded)
        failRefresh = true
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.selectTab(ProfileStatusTab.Posts)
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertEquals(5, calls.size)
        assertEquals(listOf("Replies"), viewModel.uiState.value.profile!!.statuses.map { it.statusId })
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test fun failedTabLoadDoesNotDisplayThePreviousTabsPostsOrCursor() = runTest(dispatcher) {
        val replies = CompletableDeferred<Result<TimelinePage>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> =
                if (tab == ProfileStatusTab.Replies) replies.await()
                else super.getProfileStatuses(session, accountId, tab, maxId)
        }
        val viewModel = own(AccountProfileViewModel("author", repository, auth))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.profile!!.statuses.isEmpty())
        viewModel.selectTab(ProfileStatusTab.Replies)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
        assertNull(viewModel.uiState.value.profile!!.nextMaxId)
        replies.complete(Result.failure(IllegalStateException("cannot load replies")))
        advanceUntilIdle()
        assertEquals(ProfileStatusTab.Replies, viewModel.uiState.value.selectedTab)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.profile!!.statuses.isEmpty())
    }
}
