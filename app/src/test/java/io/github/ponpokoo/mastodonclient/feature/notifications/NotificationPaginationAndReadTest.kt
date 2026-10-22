package io.github.ponpokoo.mastodonclient.feature.notifications

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPaginationAndReadTest : ScreenViewModelTestBase() {
    @Test fun refreshTimeoutKeepsConfirmedReactionTabAndRetriesBeforeAcceptingDisablement() = runTest(dispatcher) {
        var now = 1_000L
        var checks = 0
        val pending = CompletableDeferred<Result<NotificationCapabilities>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                checks++
                return when (checks) {
                    1 -> Result.success(NotificationCapabilities(true, true, checkedAtEpochMillis = now))
                    2 -> pending.await()
                    else -> Result.success(NotificationCapabilities(reactionApi = EmojiReactionApi.Unsupported, checkedAtEpochMillis = now))
                }
            }
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean) =
                Result.success(NotificationPage(listOf(testNotification("kept").copy(type = "emoji_reaction")), null, true))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }, capabilityClock = { now }))
        runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
        now += NotificationCapabilities.TTL_MILLIS
        vm.refreshNotifications(); runCurrent()
        assertTrue(vm.uiState.value.isCheckingCapabilities)
        assertTrue(vm.uiState.value.canExplore(NotificationCategory.Reactions))
        assertEquals("kept", vm.uiState.value.list(NotificationCategory.Reactions).notifications.single().id)
        advanceTimeBy(5_001); runCurrent()
        assertFalse(vm.uiState.value.isCheckingCapabilities)
        assertTrue(vm.uiState.value.canExplore(NotificationCategory.Reactions))
        now += NotificationCapabilities.RETRY_MILLIS - 1
        vm.refreshNotifications(); advanceUntilIdle()
        assertEquals(2, checks)
        now++
        vm.refreshNotifications(); advanceUntilIdle()
        assertEquals(3, checks)
        assertFalse(vm.uiState.value.canExplore(NotificationCategory.Reactions))
    }

    @Test fun expiredUnsupportedResultIsRecheckedOnRefreshAndCanEnablePleromaReactions() = runTest(dispatcher) {
        var now = 1_000L
        var checks = 0
        var reactionPages = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                checks++
                return Result.success(if (checks == 1) NotificationCapabilities(reactionApi = EmojiReactionApi.Unsupported, checkedAtEpochMillis = now)
                    else NotificationCapabilities(true, true, EmojiReactionApi.Pleroma, usesIncludeTypes = true, checkedAtEpochMillis = now))
            }
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                if (category == NotificationCategory.Reactions) reactionPages++
                return Result.success(NotificationPage(emptyList(), null, true))
            }
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }, capabilityClock = { now }))
        runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
        assertFalse(vm.uiState.value.canExplore(NotificationCategory.Reactions))
        assertEquals(0, reactionPages)
        now += 24 * 60 * 60 * 1_000L
        vm.refreshNotifications(); advanceUntilIdle()
        assertEquals(2, checks)
        assertTrue(vm.uiState.value.canExplore(NotificationCategory.Reactions))
        assertEquals(1, reactionPages)
    }
    @Test fun refreshAndForegroundUseInitialCapabilitiesWithoutCheckingAgain() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var checks = 0
        var reactions = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                checks++
                return Result.success(NotificationCapabilities(true, true))
            }
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                if (category == NotificationCategory.Reactions) reactions++
                return Result.success(NotificationPage(emptyList(), null, true))
            }
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        assertEquals(1, checks)
        vm.onNotificationsVisible(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
        assertEquals(1, reactions)
        vm.refreshNotifications(); advanceUntilIdle()
        assertEquals(2, reactions)
        vm.onAppForeground(); advanceUntilIdle()
        assertEquals(3, reactions)
        assertEquals(1, checks)
        assertFalse(vm.uiState.value.isCheckingCapabilities)
    }

    @Test fun pullRefreshDuringInitialCapabilityCheckWaitsForItWithoutDuplicatingRequests() = runTest(dispatcher) {
        val check = CompletableDeferred<Result<NotificationCapabilities>>()
        val page = CompletableDeferred<Result<NotificationPage>>()
        var checks = 0
        var reactions = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                checks++
                return check.await()
            }
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                if (category != NotificationCategory.Reactions) return super.getNotificationPage(session, category, maxId, limit, supportsTypeFiltering)
                reactions++
                return page.await()
            }
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.onNotificationsVisible(); vm.selectCategory(NotificationCategory.Reactions)
        vm.refreshNotifications(); runCurrent()
        assertEquals(1, checks)
        assertEquals(0, reactions)
        check.complete(Result.success(NotificationCapabilities(true, true))); runCurrent()
        assertEquals(1, reactions)
        assertFalse(vm.uiState.value.isCheckingCapabilities)
        assertTrue(vm.uiState.value.list(NotificationCategory.Reactions).isRefreshing)
        page.complete(Result.success(NotificationPage(emptyList(), null, true))); advanceUntilIdle()
        assertFalse(vm.uiState.value.list(NotificationCategory.Reactions).isRefreshing)
        assertEquals(1, checks)
    }

    @Test fun pageIsDisplayedAndViewedWhileMarkerIsPendingWithoutRevivingReadNotifications() = runTest(dispatcher) {
        val marker = CompletableDeferred<Result<String?>>()
        var stored = NotificationReadState()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = marker.await()
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
            override suspend fun saveNotificationReadState(session: AccountSession, state: NotificationReadState): Result<Unit> {
                stored = state; return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent(); vm.onNotificationsVisible(); runCurrent()
        assertFalse(vm.uiState.value.isLoadingNotifications)
        assertEquals(listOf("new", "old"), vm.uiState.value.notifications.map { it.id })
        assertFalse(vm.uiState.value.isReadStateReady)
        vm.onLatestNotificationsShown(setOf("new"), true); runCurrent()
        assertTrue(repository.markers.isEmpty())
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(
            TimelineStreamEvent.NotificationReceived(testNotification("live"))))
        runCurrent()
        marker.complete(Result.success("old")); advanceUntilIdle()
        assertEquals(setOf("live"), vm.uiState.value.pendingNewNotificationIds)
        assertTrue("new" in stored.viewedIds)
        assertEquals("new", stored.latestViewedAllId)
        assertEquals(listOf("one" to "new"), repository.markers)
    }

    @Test fun missingReadBoundaryAndRefreshWithoutOverlapDoNotLabelEntirePageNew() = runTest(dispatcher) {
        var refresh = false
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success("outside-page")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage((1..limit).map { testNotification("${if (refresh) "later" else "initial"}-$it") }, "tail", false))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
        assertEquals(40, vm.uiState.value.notifications.size)
        assertEquals(0, vm.uiState.value.unreadNotifications)
        refresh = true; vm.onAppForeground(); advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unreadNotifications)
    }

    @Test fun persistedViewedIdsSuppressReplayedHistoryAndStreamAfterRestart() = runTest(dispatcher) {
        var saved = NotificationReadState()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success("old")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("read"), testNotification("old")), null, true))
            override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications(readState = saved))
            override suspend fun saveNotificationReadState(session: AccountSession, state: NotificationReadState): Result<Unit> {
                saved = state; return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val first = own(NotificationsViewModel(repository, browsing))
        runCurrent(); first.onNotificationsVisible(); advanceUntilIdle()
        first.onLatestNotificationsShown(setOf("read"), false); advanceUntilIdle()
        val restarted = own(NotificationsViewModel(repository, browsing))
        runCurrent(); restarted.onNotificationsVisible(); advanceUntilIdle()
        assertEquals(0, restarted.uiState.value.unreadNotifications)
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(testNotification("read"))))
        advanceUntilIdle()
        assertEquals(0, restarted.uiState.value.unreadNotifications)
    }

    @Test fun markerTimeoutUsesDiskBoundaryAndDoesNotDelayPageDisplay() = runTest(dispatcher) {
        val marker = CompletableDeferred<Result<String?>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationMarker(session: AccountSession) = marker.await()
            override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications(lastReadId = "old"))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification("new"), testNotification("old")), null, true))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.onNotificationsVisible(); runCurrent()
        assertFalse(vm.uiState.value.isLoadingNotifications)
        advanceTimeBy(5_001); runCurrent()
        assertTrue(vm.uiState.value.isReadStateReady)
        assertEquals(setOf("new"), vm.uiState.value.pendingNewNotificationIds)
        assertNull(vm.uiState.value.notificationsError)
    }

    @Test fun categoryPagesHaveIndependentCursorsAndOnlyRequestSelectedKinds() = runTest(dispatcher) {
        val requests = mutableListOf<Triple<NotificationCategory, String?, Int>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession) = Result.success(NotificationCapabilities(true, true))
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                requests += Triple(category, maxId, limit)
                val id = "${category.name}-${if (maxId == null) "first" else "next"}"
                val type = if (category == NotificationCategory.Reactions) "emoji_reaction" else "mention"
                return Result.success(NotificationPage(listOf(testNotification(id).copy(type = type)), id, false))
            }
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Mentions); advanceUntilIdle()
        vm.loadNextNotifications(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.All); vm.loadNextNotifications(); advanceUntilIdle()
        assertEquals(listOf(
            Triple(NotificationCategory.All, null, 40),
            Triple(NotificationCategory.All, null, 40), Triple(NotificationCategory.Mentions, null, 40),
            Triple(NotificationCategory.Mentions, "Mentions-first", 40), Triple(NotificationCategory.Reactions, null, 40),
            Triple(NotificationCategory.All, "All-first", 40)), requests)
        assertEquals("Mentions-next", vm.uiState.value.list(NotificationCategory.Mentions).nextMaxId)
        assertEquals("All-next", vm.uiState.value.notificationsNextMaxId)
    }

    @Test fun unsupportedOrFailedReactionCheckBlocksPagingAndRefreshWithoutRechecking() = runTest(dispatcher) {
        for (failedCheck in listOf(false, true)) {
            var supported = false
            var checks = 0
            val requests = mutableListOf<NotificationCategory>()
            val repository = object : ScreenRepositoryFake() {
                override suspend fun getServerCapabilities(session: AccountSession): Result<NotificationCapabilities> {
                    checks++
                    return if (!supported && failedCheck) Result.failure(IllegalStateException("offline"))
                        else Result.success(NotificationCapabilities(true, supported))
                }
                override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                    requests += category
                    return Result.success(NotificationPage(emptyList(), null, true))
                }
            }
            val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
            runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
            vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
            repeat(4) { vm.loadNextNotifications(); vm.loadNextNotificationsAutomatically() }; advanceUntilIdle()
            repeat(3) {
                vm.selectCategory(NotificationCategory.All); advanceUntilIdle()
                vm.selectCategory(NotificationCategory.Reactions); advanceUntilIdle()
            }
            assertEquals(1, checks)
            assertEquals(listOf(NotificationCategory.All, NotificationCategory.All), requests)
            supported = true; vm.refreshNotifications(); advanceUntilIdle()
            assertEquals(1, checks)
            assertEquals(listOf(NotificationCategory.All, NotificationCategory.All), requests)
            assertFalse(vm.uiState.value.canExplore(NotificationCategory.Reactions))
            assertFalse(vm.uiState.value.isCheckingCapabilities)
        }
    }

    @Test fun emptyFallbackStopsAutomaticExplorationButManualPagingCanContinue() = runTest(dispatcher) {
        var mentionRequests = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> =
                if (category == NotificationCategory.All) super.getNotificationPage(session, category, maxId, limit, supportsTypeFiltering)
                else Result.success(NotificationPage(emptyList(), "cursor-${++mentionRequests}", false, false))
        }
        val vm = own(NotificationsViewModel(repository, BrowsingSession().apply { activate(testAccount) }))
        runCurrent(); vm.onNotificationsVisible(); advanceUntilIdle()
        vm.selectCategory(NotificationCategory.Mentions); advanceUntilIdle()
        repeat(6) { vm.loadNextNotificationsAutomatically(); advanceUntilIdle() }
        assertEquals(2, mentionRequests)
        assertFalse(vm.uiState.value.list(NotificationCategory.Mentions).endReached)
        vm.loadNextNotifications(); advanceUntilIdle()
        assertEquals(3, mentionRequests)
    }

    @Test fun previousAccountCapabilityResponseCannotEnableReactionsForNewAccount() = runTest(dispatcher) {
        val first = CompletableDeferred<Result<NotificationCapabilities>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun getServerCapabilities(session: AccountSession) =
                if (session == testAccount) withContext(NonCancellable) { first.await() } else Result.success(NotificationCapabilities(true, false))
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        runCurrent(); vm.onNotificationsVisible(); runCurrent()
        browsing.activate(secondAccount); runCurrent()
        first.complete(Result.success(NotificationCapabilities(true, true))); advanceUntilIdle()
        assertFalse(vm.uiState.value.canExplore(NotificationCategory.Reactions))
        assertTrue(vm.uiState.value.capabilities?.supportsTypeFiltering == true)
    }
}
