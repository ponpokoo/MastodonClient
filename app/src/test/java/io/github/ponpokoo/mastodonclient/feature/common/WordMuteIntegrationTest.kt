package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationListState
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsRefreshResult
import io.github.ponpokoo.mastodonclient.feature.notifications.ShownNewNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WordMuteIntegrationTest : ScreenViewModelTestBase() {
    @Test fun filtersBodyCwBoostsAndNotificationsRestoresWithoutFetchingAndDoesNotFilterOtherAccount() = runTest(dispatcher) {
        val body = testStatus("body").copy(contentHtml = "Hello SPOILER")
        val cw = testStatus("cw").copy(spoilerText = "Spoiler warning")
        val boost = body.copy(timelineId = "boost", boostedBy = testStatus().author)
        val kept = testStatus("kept")
        var fetches = 0
        val repository = object : ScreenRepositoryFake() {
            override val wordMutes = MutableStateFlow<Map<String, List<String>>>(emptyMap())
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> {
                fetches++; return Result.success(TimelinePage(listOf(body, cw, boost, kept), "server-cursor", false))
            }
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(listOf(testNotification().copy(status = body)), null, true))
        }
        val browsing = BrowsingSession().also { it.activate(testAccount) }
        val timeline = own(TimelineViewModel(repository, browsing)); val notifications = own(NotificationsViewModel(repository, browsing))
        advanceUntilIdle(); notifications.loadNotifications(); advanceUntilIdle()
        val initialFetches = fetches
        repository.wordMutes.value = mapOf(testAccount.sessionId to listOf("spoiler")); advanceUntilIdle()
        assertEquals(listOf("kept"), timeline.uiState.value.statuses.map { it.timelineId })
        assertEquals("server-cursor", timeline.uiState.value.nextMaxId)
        assertTrue(notifications.uiState.value.notifications.isEmpty())
        repository.wordMutes.value = emptyMap(); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size); assertEquals(initialFetches, fetches)
        assertEquals(1, notifications.uiState.value.notifications.size)
        repository.wordMutes.value = mapOf(testAccount.sessionId to listOf("spoiler"))
        browsing.activate(secondAccount); advanceUntilIdle()
        assertEquals(4, timeline.uiState.value.statuses.size)
        assertEquals(1, notifications.uiState.value.notifications.size)
    }

    @Test fun filtersAllNotificationTabsAndNewCountsWhilePreservingCursorsAndNotificationsWithoutPosts() {
        val body = testNotification("body").copy(status = testStatus().copy(contentHtml = "Hello SPOILER"))
        val cw = testNotification("cw").copy(status = testStatus().copy(spoilerText = "Spoiler warning"))
        val reaction = body.copy(id = "reaction", type = "emoji_reaction")
        val kept = testNotification("kept")
        val follow = testNotification("follow").copy(type = "follow", status = null,
            account = testStatus().author.copy(displayName = "Spoiler"))
        val ids = setOf("body", "cw", "reaction", "kept", "follow")
        val state = NotificationsUiState(
            notifications = listOf(body, cw, reaction, kept, follow), notificationsNextMaxId = "all-cursor",
            pendingNewNotificationIds = ids, highlightedNotificationIds = ids,
            refreshResult = NotificationsRefreshResult(1, true), shownNewNotice = ShownNewNotice(2, ids.size),
            lists = mapOf(
                NotificationCategory.Mentions to NotificationListState(listOf(body, cw, kept), nextMaxId = "mention-cursor"),
                NotificationCategory.Reactions to NotificationListState(listOf(reaction), nextMaxId = "reaction-cursor"),
            ),
        )
        val moderation = AccountModerationState(wordMutes = mapOf(testAccount.sessionId to listOf("spoiler")))
        val filtered = state.withModeration(moderation, testAccount)
        assertEquals(listOf("kept", "follow"), filtered.notifications.map { it.id })
        assertEquals(listOf("kept"), filtered.list(NotificationCategory.Mentions).notifications.map { it.id })
        assertTrue(filtered.list(NotificationCategory.Reactions).notifications.isEmpty())
        assertEquals("all-cursor", filtered.notificationsNextMaxId)
        assertEquals("mention-cursor", filtered.list(NotificationCategory.Mentions).nextMaxId)
        assertEquals("reaction-cursor", filtered.list(NotificationCategory.Reactions).nextMaxId)
        assertEquals(setOf("kept", "follow"), filtered.pendingNewNotificationIds)
        assertEquals(setOf("kept", "follow"), filtered.highlightedNotificationIds)
        assertEquals(2, filtered.shownNewNotice?.count)
        assertEquals(state, state.withModeration(moderation, secondAccount))

        val onlyHidden = state.copy(notifications = listOf(body), lists = emptyMap(),
            pendingNewNotificationIds = setOf("body"), highlightedNotificationIds = setOf("body"))
            .withModeration(moderation, testAccount)
        assertEquals(0, onlyHidden.unreadNotifications)
        assertTrue(onlyHidden.highlightedNotificationIds.isEmpty())
        assertNull(onlyHidden.shownNewNotice)
        assertFalse(onlyHidden.refreshResult!!.hasNewNotifications)
    }

    @Test fun hidesMatchingStreamedReplyWithoutErasingCachedNotificationsOrMutingItsAuthor() = runTest(dispatcher) {
        var requests = 0
        var cached = emptyList<TimelineNotification>()
        val repository = object : ScreenRepositoryFake() {
            override val wordMutes = MutableStateFlow(mapOf(testAccount.sessionId to listOf("spoiler")))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requests++
                return Result.success(NotificationPage(listOf(testNotification("kept")), "cursor", false))
            }
            override suspend fun cacheNotificationCategory(session: AccountSession, category: NotificationCategory,
                notifications: List<TimelineNotification>): Result<Unit> {
                if (category == NotificationCategory.All) cached = notifications
                return Result.success(Unit)
            }
        }
        val browsing = BrowsingSession().apply { activate(testAccount) }
        val vm = own(NotificationsViewModel(repository, browsing))
        vm.loadNotifications(); advanceUntilIdle()
        val reply = testNotification("live").copy(type = "reply", status = testStatus().copy(contentHtml = "SPOILER reply"))
        browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(reply)))
        advanceUntilIdle()
        assertEquals(listOf("kept"), vm.uiState.value.notifications.map { it.id })
        assertTrue(vm.uiState.value.list(NotificationCategory.Mentions).notifications.none { it.id == "live" })
        assertEquals(0, vm.uiState.value.unreadNotifications)
        assertTrue(vm.uiState.value.highlightedNotificationIds.isEmpty())
        assertTrue(cached.any { it.id == "live" })
        assertTrue(repository.moderation.value.relationships.isEmpty())
        val initialRequests = requests
        repository.wordMutes.value = emptyMap(); advanceUntilIdle()
        assertTrue(vm.uiState.value.notifications.any { it.id == "live" })
        assertTrue(vm.uiState.value.list(NotificationCategory.Mentions).notifications.any { it.id == "live" })
        assertEquals(initialRequests, requests)
    }
}
