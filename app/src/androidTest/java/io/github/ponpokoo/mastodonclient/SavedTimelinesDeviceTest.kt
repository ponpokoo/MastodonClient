package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelFactory
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesScreen
import io.github.ponpokoo.mastodonclient.feature.profile.SavedTimelinesViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SavedTimelinesDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val account = AccountSession("saved-test", "https://example.test", "me", "me", "Me", "", "fixture")
    private val browsing = BrowsingSession().apply { activate(account) }
    private lateinit var model: SavedTimelinesViewModel
    private var reply: String? = null
    private var selectedList: String? = null
    private val calls = AtomicInteger()
    private val bookmarks = AtomicInteger()
    private var response: CompletableDeferred<Unit>? = null
    private var fail = false
    private var longList = false
    private val post = TimelineStatus("post", "post", "2026-10-18T00:00:00Z",
        StatusAuthor("other", "Alice", "alice", ""), null, "Saved fixture post", "", false,
        "public", "https://example.test/@alice/post", 0, 0, 0, mediaAttachments = emptyList(), bookmarked = true)
    private val repository = object : TimelineRepository {
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        private suspend fun load() { calls.incrementAndGet(); response?.await() }
        override suspend fun getLists(session: AccountSession): Result<List<MastodonList>> {
            load()
            return if (fail) Result.failure(IOException("Fixture offline")) else Result.success(
                if (longList) List(80) { MastodonList("list-$it", "リスト $it") } else listOf(MastodonList("friends", "友だち")))
        }
        override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> {
            load()
            return if (fail) Result.failure(IOException("Fixture offline")) else Result.success(TimelinePage(listOf(post), null, true))
        }
        override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
            bookmarks.incrementAndGet()
            return Result.success(post.copy(bookmarked = bookmarked))
        }
    }

    private fun render(kind: SavedTimelineKind?) {
        rule.setContent {
            MaterialTheme {
                model = viewModel(factory = ScreenViewModelFactory { SavedTimelinesViewModel(kind, "friends", repository, browsing) })
                val actions: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory { StatusActionsViewModel(repository, browsing) })
                SavedTimelinesScreen(
                    title = "保存一覧", viewModel = model, actionsViewModel = actions,
                    currentAccountId = account.accountId, preferences = AppPreferences().let {
                        it.copy(timelineDisplay = it.timelineDisplay.copy(hiddenActions = emptySet()))
                    }, showLists = kind == null, onBack = {}, onListClick = { selectedList = it.id },
                    onStatusClick = {}, onAccountClick = {}, onMediaClick = { _, _ -> }, onOpenLink = {},
                    onReply = { reply = it }, onQuote = { _, _ -> }, onEditStatus = {},
                )
            }
        }
        rule.waitUntil(5_000) { ::model.isInitialized && model.state.value.loaded }
        rule.waitForIdle()
    }

    @Test fun listRefreshFailureKeepsRowsAndRetryAndNavigationWork() {
        render(null)
        response = CompletableDeferred()
        rule.onNodeWithTag("saved_timelines_list").performTouchInput { swipeDown() }
        rule.waitUntil(5_000) { calls.get() == 2 }
        rule.onNodeWithText("友だち").assertIsDisplayed()
        rule.runOnIdle { fail = true; response!!.complete(Unit) }
        rule.onNodeWithText("Fixture offline").assertIsDisplayed()
        rule.onNodeWithText("友だち").assertIsDisplayed()
        rule.runOnIdle { fail = false; response = null }
        rule.onNodeWithText("再試行").performClick()
        rule.waitUntil(5_000) { calls.get() == 3 }
        rule.onNodeWithText("友だち").performClick()
        rule.runOnIdle { assertEquals("friends", selectedList) }
    }

    @Test fun bookmarkReplyAndUnsaveAreConnectedAndRowRemainsVisible() {
        render(SavedTimelineKind.Bookmarks)
        rule.onNodeWithContentDescription("返信").performClick()
        rule.runOnIdle { assertEquals("post", reply) }
        rule.onNodeWithContentDescription("ブックマーク").performClick()
        rule.waitUntil(5_000) { bookmarks.get() == 1 && !model.state.value.statuses.single().bookmarked }
        rule.onNodeWithText("Saved fixture post").assertIsDisplayed()
        rule.onNodeWithContentDescription("投稿メニュー").performClick()
        rule.onNodeWithText("ブラウザで開く").assertIsDisplayed()
        rule.runOnIdle { browsing.activate(account.copy(sessionId = "another")) }
        rule.onNodeWithText("ブラウザで開く").assertDoesNotExist()
    }

    @Test fun titleReturnsScrolledListToTop() {
        longList = true
        render(null)
        rule.onNodeWithTag("saved_timelines_list").performScrollToIndex(50)
        rule.onNodeWithText("保存一覧").performClick()
        rule.waitForIdle()
        val range = rule.onNodeWithTag("saved_timelines_list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range.value())
        rule.onNodeWithText("リスト 0").assertIsDisplayed()
    }

    @Test fun emptySavedListCanBeRefreshed() {
        // A local word mute suppresses the only row without changing its server cursor.
        val words = kotlinx.coroutines.flow.MutableStateFlow(mapOf(account.sessionId to listOf("Saved fixture")))
        val emptyRepository = object : TimelineRepository by repository { override val wordMutes = words }
        rule.setContent {
            MaterialTheme {
                model = viewModel(factory = ScreenViewModelFactory { SavedTimelinesViewModel(SavedTimelineKind.Favourites, null, emptyRepository, browsing) })
                val actions: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory { StatusActionsViewModel(emptyRepository, browsing) })
                SavedTimelinesScreen("お気に入り", model, actions, account.accountId, AppPreferences(), false,
                    {}, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
            }
        }
        rule.waitUntil(5_000) { ::model.isInitialized && model.state.value.loaded }
        rule.onNodeWithText("投稿がありません").assertIsDisplayed()
        rule.onNodeWithTag("saved_timelines_list").performTouchInput { swipeDown() }
        rule.waitUntil(5_000) { calls.get() >= 2 }
    }
}
