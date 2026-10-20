package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only local fixtures: no follow requests are sent to a real server. */
class StatusFollowMenuDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("one", "https://one.example", "me", "me", "Me", "", "unused")
    private val author = StatusAuthor("original-author", "投稿者", "author@one.example", "")
    private val post = TimelineStatus("boost-row", "original-post", "2026-10-18T00:00:00Z", author,
        StatusAuthor("booster", "ブーストした人", "booster@one.example", ""),
        "<p>フォロー操作の対象</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = session
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
    }
    private var errorColor = Color.Unspecified

    private inner class Repository : TimelineRepository {
        var relationship = AccountRelationship()
        var lookup: CompletableDeferred<Result<AccountRelationship>>? = null
        var lookups = 0
        var requiresApproval = false
        var ownPost = false
        val follows = mutableListOf<Pair<String, Boolean>>()
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(listOf(post), null, true))
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) =
            Result.success(StatusDetail(if (ownPost) post.copy(author = author.copy(id = session.accountId)) else post,
                emptyList(), emptyList()))
        override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> {
            lookups++
            return lookup?.await() ?: Result.success(relationship)
        }
        override suspend fun setFollowing(session: AccountSession, accountId: String, following: Boolean): Result<AccountRelationship> {
            follows += accountId to following
            relationship = relationship.copy(following = following && !requiresApproval,
                requested = following && requiresApproval)
            return Result.success(relationship)
        }
    }

    @Test fun followsOriginalAuthorAndUnfollowsOnlyAfterRedConfirmation() {
        val repository = Repository()
        withDetail(repository) { model, theme ->
            openMenu()
            rule.onNodeWithText("フォロー解除").assertDoesNotExist()
            rule.onNodeWithText("フォロー").assertIsEnabled().performClick()
            rule.waitUntil(5_000) { model.moderationMenuState.value.relationship?.following == true }
            openMenu()
            rule.onNodeWithText("フォロー").assertDoesNotExist()
            assertRedUnfollow()
            snapshot("follow-menu-light.png")
            rule.runOnIdle { theme.value = ThemeMode.Dark }
            rule.waitForIdle()
            assertRedUnfollow()
            snapshot("follow-menu-dark.png")
            rule.onNodeWithText("フォロー解除").performClick()
            rule.onNodeWithText("キャンセル").performClick()
            rule.runOnIdle { assertEquals(listOf("original-author" to true), repository.follows) }
            openMenu()
            rule.onNodeWithText("フォロー解除").performClick()
            assertRedUnfollow()
            rule.onNode(hasText("フォロー解除") and hasClickAction()).performClick()
            rule.waitUntil(5_000) { model.moderationMenuState.value.relationship?.following == false }
            openMenu()
            rule.onNodeWithText("フォロー").assertIsEnabled()
            rule.onNodeWithText("フォロー解除").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals(listOf("original-author" to true, "original-author" to false), repository.follows)
                assertEquals(1, repository.lookups)
            }
        }
    }

    @Test fun menuWaitsUntilAllRelationshipDependentItemsAreResolved() {
        val lookup = CompletableDeferred<Result<AccountRelationship>>()
        val repository = Repository().apply { this.lookup = lookup }
        withDetail(repository) { model, _ ->
            openMenu()
            rule.waitUntil(5_000) { model.moderationMenuState.value.loading }
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.runOnIdle { lookup.complete(Result.success(AccountRelationship(following = true, muting = true))) }
            rule.onNodeWithText("フォロー解除").assertIsEnabled()
            rule.onNodeWithText("ミュートを解除").assertIsEnabled()
            rule.onNodeWithText("ブロック").assertIsEnabled()
            rule.onNodeWithText("フォロー").assertDoesNotExist()
            rule.onNodeWithText("ミュート").assertDoesNotExist()
        }
    }

    @Test fun backCancelsPendingMenuAndLateResponseCannotOpenIt() {
        val lookup = CompletableDeferred<Result<AccountRelationship>>()
        val repository = Repository().apply { this.lookup = lookup }
        withDetail(repository) { model, _ ->
            openMenu()
            rule.waitUntil(5_000) { model.moderationMenuState.value.loading }
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.runOnIdle { lookup.complete(Result.success(AccountRelationship(following = true))) }
            rule.waitUntil(5_000) { !model.moderationMenuState.value.loading }
            rule.onNode(isDialog()).assertDoesNotExist()
        }
    }

    @Test fun ownPostMenuOpensWithoutRelationshipLookup() {
        val repository = Repository().apply { ownPost = true; lookup = CompletableDeferred() }
        withDetail(repository) { _, _ ->
            openMenu()
            rule.onNodeWithText("編集").assertIsEnabled()
            rule.onNodeWithText("削除").assertIsEnabled()
            rule.runOnIdle { assertEquals(0, repository.lookups) }
        }
    }

    @Test fun failedRelationshipShowsOnlyErrorAndRetryWaitsForTheCompleteMenu() {
        val lookup = CompletableDeferred<Result<AccountRelationship>>()
        val repository = Repository().apply { this.lookup = lookup }
        withDetail(repository) { model, _ ->
            openMenu()
            rule.waitUntil(5_000) { model.moderationMenuState.value.loading }
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.onNodeWithText("フォロー").assertDoesNotExist()
            rule.onNodeWithText("フォロー解除").assertDoesNotExist()
            rule.runOnIdle { lookup.complete(Result.failure(IllegalStateException("offline"))) }
            rule.waitUntil(5_000) { model.moderationMenuState.value.error != null }
            rule.onNodeWithText("メニューを開けませんでした").assertExists()
            rule.onNodeWithText("本文をコピー").assertDoesNotExist()
            rule.onNodeWithText("フォロー").assertDoesNotExist()
            rule.onNodeWithText("フォロー解除").assertDoesNotExist()
            val retry = CompletableDeferred<Result<AccountRelationship>>()
            rule.runOnIdle { repository.lookup = retry; assertTrue(repository.follows.isEmpty()) }
            rule.onNodeWithText("再試行").performClick()
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.runOnIdle { retry.complete(Result.success(AccountRelationship())) }
            rule.onNodeWithText("フォロー").assertIsEnabled().performClick()
            rule.waitUntil(5_000) { repository.follows.size == 1 }
            rule.runOnIdle { assertEquals(listOf("original-author" to true), repository.follows) }
        }
    }

    @Test fun pendingFollowRequestCannotBeSentAgainOrShownAsUnfollow() {
        val repository = Repository().apply { requiresApproval = true }
        withDetail(repository) { model, _ ->
            openMenu()
            rule.onNodeWithText("フォロー").performClick()
            rule.waitUntil(5_000) { model.moderationMenuState.value.relationship?.requested == true }
            openMenu()
            rule.onNodeWithText("リクエスト中").assertIsNotEnabled()
            rule.onNodeWithText("フォロー").assertDoesNotExist()
            rule.onNodeWithText("フォロー解除").assertDoesNotExist()
            rule.runOnIdle { assertEquals(listOf("original-author" to true), repository.follows) }
        }
    }

    private fun withDetail(repository: Repository, verify: (StatusDetailViewModel, MutableState<ThemeMode>) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: StatusDetailViewModel
        val theme = mutableStateOf(ThemeMode.Light)
        rule.runOnIdle { model = StatusDetailViewModel(post.statusId, repository, auth); store.put("detail", model) }
        try {
            rule.setContent {
                MastodonClientTheme(themeMode = theme.value) {
                    errorColor = MaterialTheme.colorScheme.error
                    StatusDetailScreen(model, AppPreferences(), {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {})
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("投稿メニュー").fetchSemanticsNodes().isNotEmpty() }
            verify(model, theme)
        } finally { rule.runOnIdle { store.clear() } }
    }

    private fun openMenu() = rule.onNodeWithContentDescription("投稿メニュー").performClick()

    private fun assertRedUnfollow() {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNode(hasText("フォロー解除") and hasAnyAncestor(hasClickAction()) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(errorColor, results.single().layoutInput.style.color)
    }

    private fun snapshot(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), name)
        file.outputStream().use {
            rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
