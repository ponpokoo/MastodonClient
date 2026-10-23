package io.github.ponpokoo.mastodonclient

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.filters.SdkSuppress
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListScreen
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesScreen
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import io.github.ponpokoo.mastodonclient.navigation.ScreenNavHost
import io.github.ponpokoo.mastodonclient.navigation.animatedScreen
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real screens with local responses; no account operations or requests reach a server. */
@SdkSuppress(minSdkVersion = 34)
class NavigationPreviewSideEffectsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val account = AccountSession("preview-effects", "https://example.test", "me", "me", "Me", "", "fixture")
    private val author = StatusAuthor("author", "Author", "author@example.test", "")
    private val status = TimelineStatus("post", "post", "2026-10-23T00:00:00Z", author, null,
        "Fixture post", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = account
        override suspend fun getSessions() = listOf(account)
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
    }

    private enum class ListKind { Accounts, Saved, Hashtag }

    @Test fun accountListWaitsForCommitBeforeRequestingMore() = checkPagination(ListKind.Accounts)
    @Test fun savedTimelineWaitsForCommitBeforeRequestingMore() = checkPagination(ListKind.Saved)
    @Test fun hashtagTimelineWaitsForCommitBeforeRequestingMore() = checkPagination(ListKind.Hashtag)

    private fun checkPagination(kind: ListKind) {
        val requests = AtomicInteger()
        val appendResponse = CompletableDeferred<Unit>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = error("unused")
            private suspend fun posts(cursor: String?): TimelinePage {
                requests.incrementAndGet()
                if (cursor == "next") appendResponse.await()
                val items = when (cursor) {
                    null -> listOf(status, status.copy(timelineId = "post-2", statusId = "post-2"))
                    "next" -> listOf(status.copy(timelineId = "post-3", statusId = "post-3"))
                    else -> emptyList()
                }
                return TimelinePage(items, when (cursor) { null -> "next"; "next" -> "last"; else -> null }, cursor == "last")
            }
            override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?) = Result.success(posts(maxId))
            override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int) = Result.success(posts(maxId))
            override suspend fun getTag(session: AccountSession, name: String) = Result.success(SearchTag(name, "https://example.test/tags/$name"))
            override suspend fun getAccountListPage(session: AccountSession, accountId: String, followers: Boolean, maxId: String?): Result<AccountListPage> {
                val page = posts(maxId)
                return Result.success(AccountListPage(page.statuses.map { author.copy(id = it.statusId, displayName = it.statusId) }, page.nextMaxId, page.endReached))
            }
            override suspend fun getRelationships(session: AccountSession, accountIds: List<String>) =
                Result.success(accountIds.associateWith { AccountRelationship() })
        }
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner { override val lifecycle get() = registry }
        val shown = mutableStateOf(true)
        val store = ViewModelStore()
        lateinit var model: ViewModel
        var actions: StatusActionsViewModel? = null
        rule.runOnIdle {
            registry = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.STARTED }
            val browsing = BrowsingSession().apply { activate(account) }
            model = when (kind) {
                ListKind.Accounts -> AccountListViewModel("target", true, repository, auth)
                ListKind.Saved -> SavedTimelinesViewModel(SavedTimelineKind.Bookmarks, null, repository, browsing)
                ListKind.Hashtag -> HashtagTimelineViewModel("Fixture", repository, browsing)
            }
            store.put("list", model)
            if (kind != ListKind.Accounts) {
                actions = StatusActionsViewModel(repository, browsing)
                store.put("actions", requireNotNull(actions))
            }
        }
        fun loading() = when (val current = model) {
            is AccountListViewModel -> current.uiState.value.isLoading
            is SavedTimelinesViewModel -> current.state.value.loading
            is HashtagTimelineViewModel -> current.uiState.value.isLoading
            else -> error("Unknown model")
        }
        try {
            rule.waitUntil(5_000) { requests.get() == 1 && !loading() }
            rule.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        if (shown.value) when (kind) {
                            ListKind.Accounts -> AccountListScreen("Accounts", model as AccountListViewModel, {}, {})
                            ListKind.Saved -> SavedTimelinesScreen("Saved", model as SavedTimelinesViewModel, requireNotNull(actions),
                                account.accountId, AppPreferences(), false, {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
                            ListKind.Hashtag -> HashtagTimelineScreen("Fixture", model as HashtagTimelineViewModel, requireNotNull(actions),
                                account.accountId, AppPreferences(), {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
                        }
                    }
                }
            }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("Preview started pagination", 1, requests.get()); shown.value = false }
            rule.waitForIdle()
            rule.runOnIdle { shown.value = true }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("Repeated/cancelled preview started pagination", 1, requests.get())
                registry.currentState = Lifecycle.State.RESUMED
            }
            rule.waitUntil(5_000) { requests.get() == 2 }
            rule.runOnIdle { registry.currentState = Lifecycle.State.STARTED }
            rule.waitForIdle()
            rule.runOnIdle { appendResponse.complete(Unit) }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("An in-flight response started another request while paused", 2, requests.get())
                registry.currentState = Lifecycle.State.RESUMED
            }
            rule.waitUntil(5_000) { requests.get() == 3 }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("Pagination did not stop at the end", 3, requests.get()) }
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun deleteCompletionCannotPopCoverOrCancelledPreviewAndPopsOnceWhenDetailResumes() {
        val deletion = CompletableDeferred<Unit>()
        val autoBacks = AtomicInteger()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = error("unused")
            override suspend fun getStatusDetail(session: AccountSession, statusId: String) = Result.success(StatusDetail(status, emptyList(), emptyList()))
            override suspend fun deleteStatus(session: AccountSession, statusId: String): Result<Unit> {
                deletion.await()
                return Result.success(Unit)
            }
        }
        val store = ViewModelStore()
        lateinit var model: StatusDetailViewModel
        lateinit var navigation: NavHostController
        rule.runOnIdle { model = StatusDetailViewModel("post", repository, auth); store.put("detail", model) }
        try {
            rule.waitUntil(5_000) { !model.uiState.value.isLoading }
            rule.setContent {
                MaterialTheme {
                    navigation = rememberNavController()
                    ScreenNavHost(navigation, "root", Modifier.fillMaxSize()) {
                        animatedScreen("root") { Text("Root fixture") }
                        animatedScreen("detail") {
                            StatusDetailScreen(model, AppPreferences(), onBack = {
                                autoBacks.incrementAndGet()
                                navigation.popBackStack()
                            }, onReply = {}, onQuote = { _, _ -> }, onEditStatus = {}, onOpenLink = {},
                                onAccountClick = {}, onMediaClick = { _, _ -> }, onStatusClick = {})
                        }
                        animatedScreen("cover") { Text("Cover fixture") }
                    }
                }
            }
            rule.runOnIdle { navigation.navigate("detail") }
            rule.waitForIdle()
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { model.deleteStatus(status); navigation.navigate("cover") }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle { deletion.complete(Unit) }
            rule.waitUntil(5_000) { model.uiState.value.isDeleted }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("Deletion popped the covering screen", "cover", navigation.currentDestination?.route)
                assertEquals(0, autoBacks.get())
            }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            repeat(2) {
                previewBack()
                rule.runOnIdle {
                    assertEquals("Preview performed automatic back", "cover", navigation.currentDestination?.route)
                    assertEquals(0, autoBacks.get())
                }
                rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
                rule.mainClock.autoAdvance = true
                rule.waitForIdle()
                rule.runOnIdle {
                    assertEquals("Cancelled preview changed the covering destination", "cover", navigation.currentDestination?.route)
                    assertEquals("Cancelled preview triggered automatic back", 0, autoBacks.get())
                    assertEquals("Cancelled preview finished the activity", false, rule.activity.isFinishing)
                }
                rule.onNodeWithText("Cover fixture").assertIsDisplayed()
            }
            previewBack()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.onNodeWithText("Root fixture").assertIsDisplayed()
            rule.runOnIdle { assertEquals(1, autoBacks.get()); assertEquals("root", navigation.currentDestination?.route) }
        } finally { rule.mainClock.autoAdvance = true; rule.runOnIdle { store.clear() } }
    }

    private fun previewBack() {
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
        }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.7f, BackEventCompat.EDGE_LEFT))
        }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
    }
}
