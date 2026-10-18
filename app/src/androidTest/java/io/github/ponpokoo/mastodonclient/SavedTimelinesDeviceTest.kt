package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
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
    private var openedAccount: String? = null
    private var failMembersOnce = false
    private var failRemoval = false
    private var removalResponse: CompletableDeferred<Unit>? = null
    private val removalCalls = AtomicInteger()
    private var addedToList: Pair<String, String>? = null
    private val members = (0..2).map { StatusAuthor("member-$it", "アカウント $it", "member$it@remote.example", "") }.toMutableList()
    private val post = TimelineStatus("post", "post", "2026-10-18T00:00:00Z",
        StatusAuthor("other", "Alice", "alice", ""), null, "Saved fixture post", "", false,
        "public", "https://example.test/@alice/post", 0, 0, 0, mediaAttachments = emptyList(), bookmarked = true)
    private val repository = object : TimelineRepository {
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun getRelationship(session: AccountSession, accountId: String) = Result.success(AccountRelationship())
        private suspend fun load() { calls.incrementAndGet(); response?.await() }
        override suspend fun getLists(session: AccountSession): Result<List<MastodonList>> {
            load()
            return if (fail) Result.failure(IOException("Fixture offline")) else Result.success(
                if (longList) List(80) { MastodonList("list-$it", "リスト $it") } else listOf(MastodonList("friends", "友だち")))
        }
        override suspend fun getSavedTimeline(session: AccountSession, kind: SavedTimelineKind, listId: String?, maxId: String?): Result<TimelinePage> {
            load()
            return if (fail) Result.failure(IOException("Fixture offline")) else Result.success(TimelinePage(
                if (kind == SavedTimelineKind.List && members.none { it.id == "member-0" }) emptyList() else listOf(post), null, true))
        }
        override suspend fun setBookmarked(session: AccountSession, statusId: String, bookmarked: Boolean): Result<TimelineStatus> {
            bookmarks.incrementAndGet()
            return Result.success(post.copy(bookmarked = bookmarked))
        }
        override suspend fun getListAccounts(session: AccountSession, listId: String, maxId: String?): Result<AccountListPage> {
            if (maxId != null && failMembersOnce) {
                failMembersOnce = false
                return Result.failure(IOException("offline"))
            }
            val page = if (maxId == null) members.filter { it.id == "member-0" } else members.filter { it.id != "member-0" }
            return Result.success(AccountListPage(page, if (maxId == null) "next" else null, maxId != null))
        }
        override suspend fun removeAccountFromList(session: AccountSession, listId: String, accountId: String): Result<Unit> {
            assertEquals("friends", listId)
            removalCalls.incrementAndGet()
            removalResponse?.await()
            if (failRemoval) return Result.failure(IOException("offline"))
            members.removeAll { it.id == accountId }
            return Result.success(Unit)
        }
        override suspend fun addAccountToList(session: AccountSession, listId: String, accountId: String): Result<Unit> {
            addedToList = listId to accountId
            return Result.success(Unit)
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
                    onStatusClick = {}, onAccountClick = { openedAccount = it }, onMediaClick = { _, _ -> }, onOpenLink = {},
                    onReply = { reply = it }, onQuote = { _, _ -> }, onEditStatus = {},
                )
            }
        }
        rule.waitUntil(5_000) { ::model.isInitialized && model.state.value.loaded }
        rule.waitForIdle()
    }

    private fun openAccountManagement() {
        rule.onNodeWithContentDescription("リストのその他メニュー").performClick()
        rule.onNodeWithText("アカウントを管理").performClick()
        rule.waitUntil(5_000) { model.state.value.listAccounts.loaded }
        rule.onNode(isDialog()).assertExists()
    }

    @Test fun statusMenuAddsTheAuthorThroughTheCenteredListPicker() {
        render(SavedTimelineKind.Bookmarks)
        rule.onNodeWithContentDescription("投稿メニュー").performClick()
        rule.onNodeWithText("リストに追加").performClick()
        rule.onNode(hasText("追加するリストを選択") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("友だち").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertEquals("friends" to post.author.id, addedToList) }
    }

    @Test fun individualListAccountsCanBeOpenedRemovedAndVisited() {
        render(SavedTimelineKind.List)
        openAccountManagement()
        rule.onNodeWithTag("remove_list_account_member-0").performClick()
        rule.waitUntil(5_000) { model.state.value.listAccounts.removingId == null && removalCalls.get() == 1 }
        rule.onNodeWithTag("list_account_member-0").assertDoesNotExist()
        rule.onNodeWithText("リストから削除しました").assertIsDisplayed()
        rule.onNodeWithText("さらに読み込む").performClick()
        rule.waitUntil(5_000) { model.state.value.listAccounts.accounts.size == 2 }
        rule.onNodeWithText("@member1@remote.example").assertIsDisplayed()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = java.io.File(context.getExternalFilesDir(null), "list-accounts.png")
        screenshot.outputStream().use {
            rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("アカウント 1").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertEquals("member-1", openedAccount) }
        rule.onNodeWithText("Saved fixture post").assertDoesNotExist()
        rule.onNodeWithText("投稿がありません").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, calls.get()) }
    }

    @Test fun accountPageAndRemovalFailuresKeepAccountsAndCanBeRetried() {
        failMembersOnce = true
        render(SavedTimelineKind.List)
        openAccountManagement()
        rule.onNodeWithText("さらに読み込む").performClick()
        rule.onNodeWithText("アカウントを取得できませんでした").assertIsDisplayed()
        rule.onNodeWithText("アカウント 0").assertIsDisplayed()
        rule.onNodeWithText("再試行").performClick()
        rule.waitUntil(5_000) { model.state.value.listAccounts.accounts.size == 3 }
        rule.runOnIdle { failRemoval = true }
        rule.onNodeWithTag("remove_list_account_member-0").performClick()
        rule.onNodeWithText("リストから削除できませんでした").assertIsDisplayed()
        rule.onNodeWithText("アカウント 0").assertIsDisplayed()
        rule.runOnIdle { failRemoval = false; removalResponse = CompletableDeferred() }
        rule.onNodeWithTag("remove_list_account_member-0").performClick()
        rule.onNodeWithTag("remove_list_account_member-1").assertIsNotEnabled()
        rule.onNodeWithText("アカウント 0").assertIsDisplayed()
        rule.runOnIdle { removalResponse!!.complete(Unit) }
        rule.waitUntil(5_000) { model.state.value.listAccounts.accounts.size == 2 }
        rule.onNodeWithTag("list_account_member-0").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, removalCalls.get()) }
    }

    @Test fun accountManagementClosesOnSessionChangeAndEmptyListCanBeClosed() {
        members.clear()
        render(SavedTimelineKind.List)
        openAccountManagement()
        rule.onNodeWithText("さらに読み込む").performClick()
        rule.onNodeWithText("登録されているアカウントはありません").assertIsDisplayed()
        rule.onNodeWithText("閉じる").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        openAccountManagement()
        rule.runOnIdle { browsing.activate(account.copy(sessionId = "another")) }
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun listRefreshFailureKeepsRowsAndRetryAndNavigationWork() {
        render(null)
        rule.onNodeWithContentDescription("リストのその他メニュー").assertDoesNotExist()
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
        rule.onNodeWithContentDescription("リストのその他メニュー").assertDoesNotExist()
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
