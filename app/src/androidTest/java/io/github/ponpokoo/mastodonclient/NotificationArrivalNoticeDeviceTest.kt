package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationArrivalNoticeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun streamAtTopOfShortListHasOnlyATimedNotice() = checkReceipt(2, streaming = true)
    @Test fun streamAtTopOfLongListHasOnlyATimedNotice() = checkReceipt(40, streaming = true)
    @Test fun refreshAtTopOfLongListHasOnlyATimedNotice() = checkReceipt(40, streaming = false)
    @Test fun streamWhileReadingOlderNotificationsAlsoHasOnlyATimedNotice() = checkReceipt(40, streaming = true, scrolled = true)

    private fun checkReceipt(size: Int, streaming: Boolean, scrolled: Boolean = false) {
        val session = AccountSession("receipt", "https://example.test", "me", "me", "Me", "", "")
        val old = (0 until size).map { i -> TimelineNotification("old-$i", "follow", "2026-10-22T00:00:00Z",
            StatusAuthor("sender-$i", "Old $i", "sender$i@example.test", ""), null) }
        val arrived = TimelineNotification("arrival", "follow", "2026-10-22T00:01:00Z",
            StatusAuthor("new-sender", "New arrival", "new@example.test", ""), null)
        var rows = old
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(emptyList(), null, true))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(rows.take(limit), null, true))
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success<String?>("old-0")
            override suspend fun saveNotificationMarker(session: AccountSession, lastReadId: String) = Result.success(Unit)
        }
        val auth = object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
            override suspend fun restoreSession() = session
            override suspend fun getSessions() = listOf(session)
            override suspend fun logout() = Unit
        }
        val store = ViewModelStore()
        lateinit var main: MainSessionViewModel
        lateinit var timeline: TimelineViewModel
        lateinit var search: SearchViewModel
        lateinit var notifications: NotificationsViewModel
        lateinit var profile: ProfileViewModel
        lateinit var actions: StatusActionsViewModel
        rule.runOnIdle {
            main = MainSessionViewModel(auth, repository).also { store.put("main", it) }
            timeline = TimelineViewModel(repository, main.browsing).also { store.put("timeline", it) }
            search = SearchViewModel(repository, main.browsing).also { store.put("search", it) }
            notifications = NotificationsViewModel(repository, main.browsing).also { store.put("notifications", it) }
            profile = ProfileViewModel(repository, main.browsing).also { store.put("profile", it) }
            actions = StatusActionsViewModel(repository, main.browsing).also { store.put("actions", it) }
        }
        try {
            rule.waitUntil(5_000) { notifications.uiState.value.isInitialPageLoaded }
            rule.setContent {
                MaterialTheme {
                    HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                        fabPositionStore = object : FabPositionStore {
                            override val fabPosition = flowOf<FabPosition?>(null)
                            override suspend fun setFabPosition(position: FabPosition) = Unit
                            override suspend fun resetFabPosition() = Unit
                        },
                        onLoggedOut = {}, onStatusClick = {}, onCompose = {}, onQuote = { _, _ -> },
                        onOpenLink = {}, openLinksInApp = true, onOpenLinksInAppChange = {},
                        onAccountClick = {}, onMediaClick = { _, _ -> }, onSettings = {}, onFollowers = {},
                        onFollowing = {}, onEditStatus = {}, onOpenLists = {}, onOpenBookmarks = {}, onOpenFavourites = {})
                }
            }
            rule.onNodeWithTag("main_tab_notifications").performClick()
            rule.waitUntil(5_000) { !notifications.uiState.value.isLoadingNotifications && notifications.uiState.value.isReadStateReady }
            val list = rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("notification_filter_pager")))
            if (scrolled) list.performScrollToIndex(10)
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.runOnIdle {
                rows = listOf(arrived) + old
                if (streaming) main.viewModelScope.launch { main.browsing.publish(main.browsing.snapshot.value,
                    BrowsingSession.Change.Stream(TimelineStreamEvent.NotificationReceived(arrived))) }
                else notifications.refreshNotifications()
            }
            rule.waitUntil(5_000) { notifications.uiState.value.notifications.first().id == "arrival" }
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("新着1件").assertExists().assertHasNoClickAction()
            rule.onNodeWithText("↑ 新着1件").assertDoesNotExist()
            val position = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (size > 2) assertTrue("New receipts must preserve the old row's position", position > 0f)
            rule.mainClock.advanceTimeBy(1_200)
            rule.onNodeWithText("新着1件").assertExists()
            rule.mainClock.advanceTimeBy(700)
            rule.onNodeWithText("新着1件").assertDoesNotExist()
            rule.runOnIdle { notifications.refreshNotifications() }
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("新着1件").assertDoesNotExist()
            if (size > 2) rule.runOnIdle { assertEquals(setOf("arrival"), notifications.uiState.value.pendingNewNotificationIds) }
        } finally {
            rule.mainClock.autoAdvance = true
            rule.runOnIdle { store.clear() }
        }
    }
}
