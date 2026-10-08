package io.github.ponpokoo.mastodonclient

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.search.*
import io.github.ponpokoo.mastodonclient.feature.tag.*
import io.github.ponpokoo.mastodonclient.feature.timeline.SearchContent
import io.github.ponpokoo.mastodonclient.feature.status.StatusMenuDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local fixtures only: menu actions never reach a real Mastodon account. */
class SearchTagStatusMenuDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("one", "https://one.example", "me", "me", "Me", "", "unused")
    private val first = TimelineStatus("row-first", "post-first", "2026-10-07T00:00:00Z",
        StatusAuthor("first", "最初の投稿者", "first@one.example", ""), null,
        "<p>最初の本文</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val boosted = first.copy(timelineId = "boost-row", statusId = "original-post",
        author = StatusAuthor("original-author", "元の投稿者", "original@one.example", ""),
        boostedBy = StatusAuthor("booster", "ブーストした人", "booster@one.example", ""),
        contentHtml = "<p>選んだ本文</p>")

    @Test fun searchResultMenuCopiesTheSecondOriginalPost() = verifySearchMenu(explore = false)
    @Test fun trendPostMenuCopiesTheSecondOriginalPost() = verifySearchMenu(explore = true)

    private fun verifySearchMenu(explore: Boolean) {
        var selected: TimelineStatus? = null
        val posts = listOf(first, boosted)
        val state = if (explore) SearchUiState(sessionKey = "one") else SearchUiState(
            sessionKey = "one", searchQuery = "投稿", isSearchActive = true,
            tabs = mapOf(SearchTarget.Posts to SearchTabState(
                results = SearchResults(emptyList(), posts, emptyList()), endReached = true)))
        rule.setContent { MaterialTheme {
            var menu by remember { mutableStateOf<TimelineStatus?>(null) }
            SearchContent(state, PaddingValues(), onQueryChanged = {}, onSearch = {}, onEnterSearch = {},
                onBack = {}, onClear = {}, onSelectTarget = {}, onLoadMore = {}, onRetry = {},
                onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {},
                onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                onAccountClick = {}, onMediaClick = { _, _ -> },
                onMoreClick = { selected = it; menu = it },
                exploreState = ExploreUiState(tabs = mapOf(ExploreFeed.Posts to ExploreTabState(ExplorePage(statuses = posts)))))
            menu?.let { post -> StatusMenuDialog(post, false, { menu = null }, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        } }
        rule.onAllNodesWithContentDescription("投稿メニュー")[1].performClick()
        rule.onNode(hasText("元の投稿者") and hasAnyAncestor(isDialog())).assertExists()
        rule.onNodeWithText("本文をコピー").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(boosted, selected)
            val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("選んだ本文", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    private inner class Repository : TimelineRepository {
        var posts = listOf(first, boosted)
        var failLookup = false
        var failPin = false
        val reports = mutableListOf<Triple<String, String, String>>()
        val deletes = mutableListOf<String>()
        val pins = mutableListOf<String>()
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(posts, null, true))
        override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int) =
            Result.success(TimelinePage(posts, null, true))
        override suspend fun getTag(session: AccountSession, name: String) =
            Result.success(SearchTag(name, "https://one.example/tags/test", following = false))
        override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> =
            if (failLookup) Result.failure(IllegalStateException("offline")) else Result.success(AccountRelationship())
        override suspend fun reportStatus(session: AccountSession, accountId: String, statusId: String, comment: String): Result<Unit> {
            reports += Triple(accountId, statusId, comment)
            return Result.success(Unit)
        }
        override suspend fun deleteStatus(session: AccountSession, statusId: String): Result<Unit> {
            deletes += statusId
            return Result.success(Unit)
        }
        override suspend fun setPinned(session: AccountSession, statusId: String, pinned: Boolean): Result<TimelineStatus> {
            pins += statusId
            return if (failPin) Result.failure(IllegalStateException("固定に失敗"))
            else Result.success(posts.first { it.statusId == statusId }.copy(pinned = pinned))
        }
    }

    @Test fun tagMenuRetriesRelationshipAndReportsTheSecondOriginalPost() {
        val repository = Repository().apply { failLookup = true }
        withTag(repository) { _, _ ->
            openSecondMenu()
            rule.onNodeWithText("ミュート").assertIsNotEnabled()
            rule.runOnIdle { repository.failLookup = false }
            rule.onNodeWithText("再試行").performClick()
            rule.onNodeWithText("ミュート").assertIsEnabled()
            rule.onNodeWithText("編集").assertDoesNotExist()
            rule.onNodeWithText("報告").performClick()
            rule.onNodeWithText("理由・補足").performTextInput("選択した投稿の報告")
            rule.onNodeWithText("送信").performClick()
            rule.waitUntil(5_000) { repository.reports.isNotEmpty() }
            rule.onNodeWithText("報告を送信しました").assertExists()
            rule.runOnIdle { assertEquals(listOf(Triple("original-author", "original-post", "選択した投稿の報告")), repository.reports) }
        }
    }

    @Test fun tagOwnBoostMenuEditsPinsAndDeletesOriginalIdWithCancelAndFailureFeedback() {
        val own = boosted.copy(author = boosted.author.copy(id = session.accountId))
        val repository = Repository().apply { posts = listOf(first, own); failPin = true }
        var edited: String? = null
        withTag(repository, onEdit = { edited = it }) { _, model ->
            openSecondMenu()
            rule.onNodeWithText("報告").assertDoesNotExist()
            rule.onNodeWithText("編集").performClick()
            rule.runOnIdle { assertEquals("original-post", edited) }
            openSecondMenu()
            rule.onNodeWithText("プロフィールに固定").performClick()
            rule.waitUntil(5_000) { repository.pins.size == 1 }
            rule.onNodeWithText("固定に失敗").assertExists()
            rule.runOnIdle { repository.failPin = false }
            openSecondMenu()
            rule.onNodeWithText("プロフィールに固定").performClick()
            rule.waitUntil(5_000) { model.uiState.value.statuses.last().pinned }
            openSecondMenu()
            rule.onNodeWithText("プロフィールへの固定解除").assertExists()
            rule.onNodeWithText("削除").performClick()
            rule.onNodeWithText("キャンセル").performClick()
            rule.runOnIdle { assertTrue(repository.deletes.isEmpty()) }
            openSecondMenu()
            rule.onNodeWithText("削除").performClick()
            rule.onNodeWithText("実行").performClick()
            rule.waitUntil(5_000) { model.uiState.value.statuses.size == 1 }
            rule.runOnIdle {
                assertEquals(listOf("original-post", "original-post"), repository.pins)
                assertEquals(listOf("original-post"), repository.deletes)
                assertEquals("post-first", model.uiState.value.statuses.single().statusId)
            }
        }
    }

    @Test fun accountChangeDiscardsTagMenuConfirmationAndReport() {
        withTag(Repository()) { browsing, model ->
            openSecondMenu()
            switchAccount(browsing, model)
            rule.onNode(isDialog()).assertDoesNotExist()
            openSecondMenu()
            rule.onNodeWithText("フォロー解除").performClick()
            switchAccount(browsing, model)
            rule.onNodeWithText("実行").assertDoesNotExist()
            openSecondMenu()
            rule.onNodeWithText("報告").performClick()
            rule.onNodeWithText("理由・補足").performTextInput("旧アカウントの入力")
            switchAccount(browsing, model)
            rule.onNodeWithText("送信").assertDoesNotExist()
        }
    }

    private fun switchAccount(browsing: BrowsingSession, model: HashtagTimelineViewModel) {
        var generation = 0L
        rule.runOnIdle {
            val snapshot = browsing.snapshot.value
            browsing.activate(session.copy(sessionId = "account-${snapshot.generation}"))
            generation = browsing.snapshot.value.generation
        }
        rule.waitUntil(5_000) { model.uiState.value.sessionKey?.endsWith(":$generation") == true && !model.uiState.value.isLoading }
        rule.waitForIdle()
    }

    private fun openSecondMenu() {
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("投稿メニュー").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithContentDescription("投稿メニュー")[1].performClick()
    }

    private fun withTag(repository: Repository, onEdit: (String) -> Unit = {},
        verify: (BrowsingSession, HashtagTimelineViewModel) -> Unit) {
        val store = ViewModelStore()
        val browsing = BrowsingSession().apply { activate(session) }
        lateinit var model: HashtagTimelineViewModel
        lateinit var actions: StatusActionsViewModel
        rule.runOnIdle {
            model = HashtagTimelineViewModel("test", repository, browsing)
            actions = StatusActionsViewModel(repository, browsing)
            store.put("tag", model); store.put("actions", actions)
        }
        try {
            rule.setContent { MaterialTheme {
                val snapshot by browsing.snapshot.collectAsStateWithLifecycle()
                HashtagTimelineScreen("test", model, actions, snapshot.account?.accountId, AppPreferences(),
                    {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, onEdit)
            } }
            verify(browsing, model)
        } finally { rule.runOnIdle { store.clear() } }
    }
}
