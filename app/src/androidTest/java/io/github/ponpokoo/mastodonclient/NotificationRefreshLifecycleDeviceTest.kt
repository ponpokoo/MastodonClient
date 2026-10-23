package io.github.ponpokoo.mastodonclient

import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.notifications.*
import io.github.ponpokoo.mastodonclient.feature.timeline.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationRefreshLifecycleDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun uncommittedPreviewDoesNotRefreshOrClearNotificationHighlights() {
        val requests = AtomicInteger()
        val session = AccountSession("preview", "https://example.test", "me", "me", "Me", "", "")
        val notification = TimelineNotification("live", "follow", "2026-10-23T00:00:00Z",
            StatusAuthor("author", "Author", "author@example.test", ""), null)
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = error("unused")
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                requests.incrementAndGet()
                return Result.success(NotificationPage(emptyList(), null, true))
            }
        }
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        val owner = object : androidx.lifecycle.LifecycleOwner { override val lifecycle get() = registry }
        val shown = mutableStateOf(true)
        val store = ViewModelStore()
        lateinit var browsing: BrowsingSession
        lateinit var model: NotificationsViewModel
        rule.runOnIdle {
            registry = androidx.lifecycle.LifecycleRegistry(owner)
            registry.currentState = Lifecycle.State.STARTED
            browsing = BrowsingSession().apply { activate(session) }
            model = NotificationsViewModel(repository, browsing)
            store.put("preview", model)
        }
        try {
            rule.waitUntil(5_000) { model.uiState.value.isInitialPageLoaded }
            rule.runOnIdle {
                kotlinx.coroutines.runBlocking {
                    browsing.publish(browsing.snapshot.value, BrowsingSession.Change.Stream(
                        TimelineStreamEvent.NotificationReceived(notification)))
                }
            }
            rule.waitUntil(5_000) { "live" in model.uiState.value.highlightedNotificationIds }
            val beforePreview = requests.get()
            rule.setContent {
                androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                    if (shown.value) NotificationsLifecycleEffect(model, true)
                    Text("Preview")
                }
            }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(beforePreview, requests.get()); shown.value = false }
            rule.waitForIdle()
            rule.runOnIdle {
                assertTrue("Cancelled preview cleared highlights", "live" in model.uiState.value.highlightedNotificationIds)
                shown.value = true
            }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(beforePreview, requests.get()); registry.currentState = Lifecycle.State.RESUMED }
            rule.waitUntil(5_000) { requests.get() > beforePreview && !model.uiState.value.isLoadingNotifications }
            val afterCommit = requests.get()
            rule.runOnIdle { registry.currentState = Lifecycle.State.STARTED }
            rule.waitForIdle()
            rule.runOnIdle { registry.currentState = Lifecycle.State.RESUMED }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("Cancelled outgoing preview triggered a refresh", afterCommit, requests.get()) }
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun tabReturnAllowsManualPullAndForegroundReturnRefreshesWhileAnotherTabIsSelected() {
        val requests = AtomicInteger()
        val manual = CompletableDeferred<Result<NotificationPage>>()
        val visible = mutableStateOf(false)
        val author = StatusAuthor("author", "通知した人", "test@example.test", "")
        fun page(id: String) = NotificationPage(listOf(
            TimelineNotification(id, "follow", "2026-10-05T00:00:00Z", author, null),
        ), null, true)
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> = error("unused")
            override val moderation = kotlinx.coroutines.flow.MutableStateFlow(AccountModerationState())
            override suspend fun getServerCapabilities(session: AccountSession) = Result.success(NotificationCapabilities())
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean) =
                getNotifications(session, maxId, limit)
            override suspend fun cacheNotificationCategory(session: AccountSession, category: NotificationCategory, notifications: List<TimelineNotification>) = Result.success(Unit)
            override suspend fun saveNotificationReadState(session: AccountSession, state: NotificationReadState) = Result.success(Unit)
            override suspend fun getCachedNotifications(session: AccountSession) = Result.success(CachedNotifications(emptyList()))
            override suspend fun getNotificationMarker(session: AccountSession) = Result.success<String?>(null)
            override suspend fun cacheNotifications(session: AccountSession, notifications: List<TimelineNotification>) = Result.success(Unit)
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int): Result<NotificationPage> {
                val number = requests.incrementAndGet()
                return if (number == 4) manual.await() else Result.success(page("notification-$number"))
            }
        }
        lateinit var viewModel: NotificationsViewModel
        val store = ViewModelStore()
        rule.runOnIdle {
            val browsing = BrowsingSession().apply {
                activate(AccountSession("test", "https://example.test", "me", "me", "Me", "", ""))
            }
            viewModel = NotificationsViewModel(repository, browsing)
            store.put("notifications", viewModel)
        }
        try {
            rule.setContent { MastodonClientTheme(themeMode = ThemeMode.Dark) { Surface(Modifier.fillMaxSize()) {
                NotificationsLifecycleEffect(viewModel, visible.value)
                val state by viewModel.uiState.collectAsState()
                val listStates = List(3) { rememberLazyListState() }
                Column {
                    Row {
                        Button(onClick = { visible.value = false }) { Text("ホームタブ") }
                        Button(onClick = { visible.value = true }) { Text("通知タブ") }
                    }
                    if (visible.value) NotificationsContent(
                        state = state, padding = PaddingValues(), onRefresh = viewModel::refreshNotifications,
                        onLoadMore = {}, onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {},
                        onFavourite = {}, onBookmark = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                        preferences = AppPreferences(), listStates = listStates, selectedFilter = NotificationFilter.All,
                        onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null,
                    ) else Text("ホームを表示中")
                }
            } } }
            rule.waitUntil(5_000) { viewModel.uiState.value.isInitialPageLoaded }
            rule.onNodeWithText("ホームを表示中").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals(1, requests.get())
                assertEquals("notification-1", viewModel.uiState.value.notifications.first().id)
            }
            rule.onNodeWithText("通知タブ").performClick()
            rule.onNodeWithTag("notifications_screen").assertIsDisplayed()
            rule.onNodeWithText("ホームタブ").performClick()
            rule.onNodeWithText("通知タブ").performClick()
            rule.runOnIdle { assertEquals(3, requests.get()) }

            rule.onNodeWithTag("notifications_screen").performTouchInput {
                swipe(start = Offset(centerX, height * 0.35f), end = Offset(centerX, height * 0.85f), durationMillis = 600)
            }
            rule.waitUntil(5_000) { requests.get() == 4 }
            rule.runOnIdle {
                assertTrue(viewModel.uiState.value.isLoadingNotifications)
                assertTrue(viewModel.uiState.value.isPullRefreshingNotifications)
                manual.complete(Result.success(page("notification-4")))
            }
            rule.waitUntil(5_000) { !viewModel.uiState.value.isLoadingNotifications }
            rule.runOnIdle { assertFalse(viewModel.uiState.value.isPullRefreshingNotifications) }

            rule.onNodeWithText("ホームタブ").performClick()
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { requests.get() == 5 && !viewModel.uiState.value.isLoadingNotifications }
            rule.runOnIdle { assertEquals("notification-5", viewModel.uiState.value.notifications.first().id) }
            rule.onNodeWithText("通知タブ").performClick()
            rule.runOnIdle { assertEquals(6, requests.get()) }
        } finally { rule.runOnIdle { store.clear() } }
    }
}
