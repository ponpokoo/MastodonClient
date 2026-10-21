package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsContent
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NotificationPagingDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val account = AccountSession("test", "https://example.test", "me", "me", "Me", "", "")

    private fun show(vm: NotificationsViewModel, startLoading: CompletableDeferred<Unit>? = null) {
        rule.setContent { MastodonClientTheme { Surface(Modifier.fillMaxSize()) {
            val state by vm.uiState.collectAsState()
            LaunchedEffect(vm) { startLoading?.await(); vm.onNotificationsVisible() }
            NotificationsContent(state = state, padding = PaddingValues(),
                onRefresh = vm::refreshNotifications, onLoadMore = vm::loadNextNotifications,
                onAutoLoadMore = vm::loadNextNotificationsAutomatically,
                onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {},
                onBookmark = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                preferences = AppPreferences(), listStates = List(3) { rememberLazyListState() },
                selectedFilter = state.selectedCategory, onSelectFilter = vm::selectCategory,
                newNoticeMessage = null, onNewNoticeClick = null)
        } } }
    }

    @Test fun unsupportedReactionTabReusesCheckOnNavigationAndRefresh() {
        val checks = AtomicInteger()
        val reactions = AtomicInteger()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> = error("unused")
            override suspend fun getServerCapabilities(session: AccountSession) =
                Result.success(NotificationCapabilities(true, checks.incrementAndGet() != 1))
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean): Result<NotificationPage> {
                if (category == NotificationCategory.Reactions) reactions.incrementAndGet()
                return Result.success(NotificationPage(emptyList(), null, true))
            }
        }
        val store = ViewModelStore()
        lateinit var vm: NotificationsViewModel
        rule.runOnIdle { vm = NotificationsViewModel(repository, BrowsingSession().apply { activate(account) }); store.put("notifications", vm) }
        try {
            show(vm)
            rule.waitUntil { vm.uiState.value.isInitialPageLoaded }
            rule.onNodeWithTag("notification_filter_reactions").performClick()
            rule.waitUntil { vm.uiState.value.selectedCategory == NotificationCategory.Reactions }
            rule.runOnIdle { assertEquals(0, reactions.get()) }
            rule.waitUntil { !vm.uiState.value.isCheckingCapabilities }
            rule.onNodeWithText("現在サポートしていません").assertExists()
            rule.onNodeWithText("再確認").assertDoesNotExist()
            rule.onNodeWithText("さらに読み込む").assertDoesNotExist()
            rule.runOnIdle { vm.loadNextNotifications(); vm.loadNextNotificationsAutomatically(); assertEquals(0, reactions.get()) }
            repeat(2) {
                rule.onNodeWithTag("notification_filter_mentions").performClick()
                rule.waitUntil { vm.uiState.value.selectedCategory == NotificationCategory.Mentions }
                rule.onNodeWithTag("notification_filter_reactions").performClick()
                rule.waitUntil { vm.uiState.value.selectedCategory == NotificationCategory.Reactions }
                rule.onNodeWithText("現在サポートしていません").assertExists()
                rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
            }
            rule.runOnIdle { assertEquals(1, checks.get()); assertEquals(0, reactions.get()); vm.refreshNotifications() }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(1, checks.get()); assertEquals(0, reactions.get()) }
            rule.onNodeWithText("現在サポートしていません").assertExists()
            rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun emptyMessageWaitsForInitialRequestAndItsResponse() {
        val startLoading = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Result<NotificationPage>>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> = error("unused")
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean) = response.await()
        }
        val store = ViewModelStore()
        lateinit var vm: NotificationsViewModel
        rule.runOnIdle { vm = NotificationsViewModel(repository, BrowsingSession().apply { activate(account) }); store.put("notifications", vm) }
        try {
            show(vm, startLoading)
            rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
            rule.onNodeWithText("読み込んだ範囲に該当する通知はありません").assertDoesNotExist()
            rule.runOnIdle { startLoading.complete(Unit) }
            rule.waitUntil { vm.uiState.value.isLoadingNotifications }
            rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
            rule.onNodeWithText("読み込んだ範囲に該当する通知はありません").assertDoesNotExist()
            rule.runOnIdle { response.complete(Result.success(NotificationPage(emptyList(), null, false))) }
            rule.waitUntil { vm.uiState.value.isInitialPageLoaded }
            rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
            rule.onNodeWithText("読み込んだ範囲に該当する通知はありません").assertExists()
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun emptyMentionFallbackStopsAfterTwoPagesAndStillAllowsManualHistory() {
        val requests = AtomicInteger()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int): Result<TimelinePage> = error("unused")
            override suspend fun getNotificationPage(session: AccountSession, category: NotificationCategory, maxId: String?, limit: Int, supportsTypeFiltering: Boolean) =
                if (category == NotificationCategory.All) Result.success(NotificationPage(emptyList(), null, true))
                else Result.success(NotificationPage(emptyList(), "cursor-${requests.incrementAndGet()}", false, false))
        }
        val store = ViewModelStore()
        lateinit var vm: NotificationsViewModel
        rule.runOnIdle { vm = NotificationsViewModel(repository, BrowsingSession().apply { activate(account) }); store.put("notifications", vm) }
        try {
            show(vm)
            rule.waitUntil { vm.uiState.value.isInitialPageLoaded }
            rule.onNodeWithTag("notification_filter_mentions").performClick()
            // Automatic paging observes layout only once the tab movement has settled.
            rule.waitForIdle()
            rule.waitUntil(5_000) { requests.get() >= 2 }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(2, requests.get()); assertFalse(vm.uiState.value.list(NotificationCategory.Mentions).endReached) }
            rule.onNodeWithText("さらに読み込む").performClick()
            rule.waitUntil { requests.get() >= 3 }
            rule.waitForIdle()
            rule.runOnIdle { assertTrue(requests.get() <= 4) }
        } finally { rule.runOnIdle { store.clear() } }
    }
}
