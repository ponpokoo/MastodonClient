package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationPrefetchDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun completedPrefetchIsUnreadOnHomeAndAcknowledgedOnlyAfterRowsAreDisplayed() =
        checkAcknowledgement(markerPending = false)

    @Test fun darkThemeKeepsPrefetchedNewRowHighlightedAfterAcknowledgementUntilLeaving() =
        checkAcknowledgement(markerPending = false, darkTheme = true)

    @Test fun openingPrefetchedRowsBeforeMarkerCompletesDoesNotReviveViewedNotifications() =
        checkAcknowledgement(markerPending = true)

    @Test fun notificationLaunchAfterPrefetchKeepsNewRowHighlighted() =
        checkAcknowledgement(markerPending = false, systemOpen = SystemOpen.CompletedPrefetch)

    @Test fun notificationLaunchDuringPrefetchKeepsNewRowHighlightedAfterQueuedRefresh() =
        checkAcknowledgement(markerPending = false, systemOpen = SystemOpen.LoadingPrefetch)

    @Test fun notificationLaunchForAnotherAccountKeepsNewRowHighlighted() =
        checkAcknowledgement(markerPending = false, systemOpen = SystemOpen.OtherAccount)

    @Test fun notificationLaunchBeforeMarkerCompletesHighlightsRowsWithoutRevivingUnread() =
        checkAcknowledgement(markerPending = true, systemOpen = SystemOpen.CompletedPrefetch)

    @Test fun prefetchedRowsStayVisibleWhileTabOpenRefreshIsWaiting() =
        checkAcknowledgement(markerPending = false, delayTabRefresh = true)

    private enum class SystemOpen { None, CompletedPrefetch, LoadingPrefetch, OtherAccount }

    private fun checkAcknowledgement(markerPending: Boolean, darkTheme: Boolean = false,
        systemOpen: SystemOpen = SystemOpen.None, delayTabRefresh: Boolean = false) {
        val account = AccountSession("prefetch", "https://example.test", "me", "me", "Me", "", "")
        val otherAccount = account.copy(sessionId = "other", instanceUrl = "https://other.example.test")
        val author = StatusAuthor("sender", "先行取得した通知者", "sender@example.test", "")
        val rows = listOf("new", "old").mapIndexed { index, id ->
            TimelineNotification(id, "follow", "2026-10-22T00:00:0${1 - index}Z", author, null)
        }
        val marker = CompletableDeferred<Result<String?>>()
        if (!markerPending) marker.complete(Result.success("old"))
        val requests = AtomicInteger()
        val latestPageRequests = AtomicInteger()
        val prefetch = CompletableDeferred<Unit>()
        val tabRefresh = CompletableDeferred<Unit>()
        val handled = AtomicInteger()
        val markers = CopyOnWriteArrayList<Pair<String, String>>()
        val dismissals = CopyOnWriteArrayList<Pair<String, Set<String>>>()
        val reads = CopyOnWriteArrayList<NotificationReadState>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(emptyList(), null, true))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                val number = requests.incrementAndGet()
                if (number == 1 && systemOpen == SystemOpen.LoadingPrefetch) prefetch.await()
                if (maxId == null && latestPageRequests.incrementAndGet() == 2 && delayTabRefresh) tabRefresh.await()
                return Result.success(NotificationPage(rows, "old", false))
            }
            override suspend fun getNotificationMarker(session: AccountSession) = marker.await()
            override suspend fun saveNotificationMarker(session: AccountSession, lastReadId: String): Result<Unit> {
                markers += session.sessionId to lastReadId
                return Result.success(Unit)
            }
            override suspend fun saveNotificationReadState(session: AccountSession, state: NotificationReadState): Result<Unit> {
                reads += state
                return Result.success(Unit)
            }
        }
        val system = object : SystemNotificationRepository {
            override suspend fun show(session: AccountSession, notification: TimelineNotification, isCurrent: () -> Boolean) = Unit
            override suspend fun dismissForAccount(sessionId: String) = error("Must dismiss only displayed notifications")
            override suspend fun dismissRead(sessionId: String, notificationIds: Set<String>) {
                dismissals += sessionId to notificationIds
            }
        }
        val auth = object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
            override suspend fun restoreSession() = if (systemOpen == SystemOpen.OtherAccount) otherAccount else account
            override suspend fun getSessions() = listOf(account, otherAccount)
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
            notifications = NotificationsViewModel(repository, main.browsing, system).also { store.put("notifications", it) }
            profile = ProfileViewModel(repository, main.browsing).also { store.put("profile", it) }
            actions = StatusActionsViewModel(repository, main.browsing).also { store.put("actions", it) }
        }
        try {
            // Deliberately finish the page before HomeTimelineScreen installs its effects.
            if (systemOpen == SystemOpen.LoadingPrefetch) rule.waitUntil(5_000) { requests.get() == 1 }
            else rule.waitUntil(5_000) { notifications.uiState.value.isInitialPageLoaded }
            val request = if (systemOpen == SystemOpen.None) null else NotificationOpenIntent.parse(
                NotificationOpenIntent.create(InstrumentationRegistry.getInstrumentation().targetContext, account.sessionId, "new"))
            rule.setContent {
                MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                    HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                        notificationOpenRequest = request,
                        onNotificationOpenHandled = { handled.incrementAndGet() },
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
            if (systemOpen == SystemOpen.None) {
                rule.onNodeWithTag("main_tab_home").assertIsSelected()
                rule.runOnIdle {
                    assertEquals(1, requests.get())
                    assertTrue(markers.isEmpty())
                    assertTrue(dismissals.isEmpty())
                    assertTrue(reads.all { it.viewedIds.isEmpty() && it.latestViewedAllId == null })
                    if (!markerPending) {
                        assertEquals(setOf("new"), notifications.uiState.value.pendingNewNotificationIds)
                        assertEquals(setOf("new"), notifications.uiState.value.highlightedNotificationIds)
                    }
                }
                rule.onNodeWithTag("main_tab_notifications").performClick()
            } else {
                rule.waitUntil(5_000) { handled.get() == 1 }
                rule.runOnIdle { prefetch.complete(Unit) }
                rule.waitUntil(5_000) { !notifications.uiState.value.isLoadingNotifications }
                rule.onNodeWithTag("main_tab_notifications").assertIsSelected()
                rule.runOnIdle {
                    assertEquals(account.sessionId, main.uiState.value.session?.sessionId)
                    assertTrue(requests.get() >= 2)
                }
            }
            rule.onAllNodesWithText(author.displayName).onFirst().assertIsDisplayed()
            if (delayTabRefresh) {
                rule.runOnIdle {
                    assertTrue(notifications.uiState.value.isLoadingNotifications)
                    assertFalse(notifications.uiState.value.isPullRefreshingNotifications)
                    assertTrue(markers.isEmpty())
                }
                assertRowBackgrounds(highlighted = true)
                rule.runOnIdle { tabRefresh.complete(Unit) }
            }
            rule.waitUntil(5_000) { dismissals.isNotEmpty() }
            rule.runOnIdle {
                assertTrue(reads.any { it.latestViewedAllId == "new" })
                assertTrue(dismissals.all { it.first == account.sessionId && it.second == setOf("new", "old") })
                if (markerPending) {
                    assertTrue(markers.isEmpty())
                    marker.complete(Result.success("old"))
                }
            }
            rule.waitUntil(5_000) { markers.isNotEmpty() && notifications.uiState.value.isReadStateReady }
            rule.runOnIdle {
                assertEquals(listOf(account.sessionId to "new"), markers.toList())
                assertTrue(notifications.uiState.value.pendingNewNotificationIds.isEmpty())
            }
            run {
                rule.runOnIdle { assertEquals(setOf("new"), notifications.uiState.value.highlightedNotificationIds) }
                assertRowBackgrounds(highlighted = true)
                rule.onNodeWithTag("main_tab_home").performClick()
                rule.runOnIdle { assertTrue(notifications.uiState.value.highlightedNotificationIds.isEmpty()) }
                rule.onNodeWithTag("main_tab_notifications").performClick()
                assertRowBackgrounds(highlighted = false)
            }
        } finally { rule.runOnIdle { store.clear() } }
    }

    private fun assertRowBackgrounds(highlighted: Boolean) {
        val avatars = rule.onAllNodesWithTag("notification_actor_avatar", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, avatars.size)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // Sample empty space at each row's right edge, away from text, icons and the notice.
        val newRow = pixels[pixels.width - 4, avatars[0].boundsInRoot.center.y.toInt()]
        val oldRow = pixels[pixels.width - 4, avatars[1].boundsInRoot.center.y.toInt()]
        if (highlighted) assertNotEquals("New and old rows must have different backgrounds", oldRow, newRow)
        else assertEquals("Leaving the tab must restore the normal background", oldRow, newRow)
    }
}
