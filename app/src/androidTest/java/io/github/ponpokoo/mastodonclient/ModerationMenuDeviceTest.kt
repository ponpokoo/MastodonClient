package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.detail.*
import io.github.ponpokoo.mastodonclient.feature.profile.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ModerationMenuDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("one", "https://one.example", "me", "me", "Me", "", "unused")
    private val author = StatusAuthor("author", "対象アカウント", "target@one.example", "")
    private val post = TimelineStatus("row", "post", "2026-10-05T00:00:00Z", author, null,
        "<p>対象の投稿</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = session
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
    }
    private inner class Repository : TimelineRepository {
        override val moderation = MutableStateFlow(AccountModerationState())
        var relationship = AccountRelationship(muting = true, blocking = true)
        var failLookup = false
        var paginatedPosts = false
        var publishModeration = false
        var profilePageCalls = 0
        val changes = mutableListOf<Pair<String, Boolean>>()
        val reports = mutableListOf<Triple<String, String, String>>()
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(listOf(post), null, true))
        override suspend fun getStatusDetail(session: AccountSession, statusId: String) = Result.success(StatusDetail(post, emptyList(), emptyList()))
        override suspend fun getRelationship(session: AccountSession, accountId: String): Result<AccountRelationship> =
            if (failLookup) Result.failure(IllegalStateException("offline")) else Result.success(relationship)
        override suspend fun getProfile(session: AccountSession, accountId: String) = Result.success(UserProfile(
            author, "", "", 0, 0, 1, emptyList(), url = "https://one.example/@target", isOwnProfile = false, endReached = true))
        override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?): Result<TimelinePage> {
            profilePageCalls++
            return Result.success(if (paginatedPosts) TimelinePage((1..20).map {
                post.copy(timelineId = "row-$profilePageCalls-$it", statusId = "post-$profilePageCalls-$it")
            }, "older-$profilePageCalls", false)
                else TimelinePage(emptyList(), null, true))
        }
        override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
            changes += "mute" to muted
            relationship = relationship.copy(muting = muted)
            if (publishModeration) moderation.value = moderation.value.changed(session, accountId, relationship)
            return Result.success(relationship)
        }
        override suspend fun setBlocked(session: AccountSession, accountId: String, blocked: Boolean): Result<AccountRelationship> {
            changes += "block" to blocked
            relationship = relationship.copy(blocking = blocked)
            if (publishModeration) moderation.value = moderation.value.changed(session, accountId, relationship)
            return Result.success(relationship)
        }
        override suspend fun reportStatus(session: AccountSession, accountId: String, statusId: String, comment: String): Result<Unit> {
            reports += Triple(accountId, statusId, comment)
            return Result.success(Unit)
        }
    }

    @Test fun postMenuConfirmsUnmuteAndUnblockAndReportsTheChosenPost() {
        val repository = Repository()
        val store = ViewModelStore()
        lateinit var model: StatusDetailViewModel
        val theme = mutableStateOf(ThemeMode.Light)
        rule.runOnIdle { model = StatusDetailViewModel("post", repository, auth); store.put("detail", model) }
        try {
            rule.setContent {
                MastodonClientTheme(themeMode = theme.value) {
                    StatusDetailScreen(model, AppPreferences(), {}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {})
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("投稿メニュー").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("投稿メニュー").performClick()
            rule.onNodeWithText("ミュート（解除）").assertIsEnabled()
            snapshot("moderation-light.png")
            rule.runOnIdle { theme.value = ThemeMode.Dark }
            rule.waitForIdle()
            snapshot("moderation-dark.png")
            rule.onNodeWithText("ミュート（解除）").performClick()
            rule.onNodeWithText("キャンセル").performClick()
            rule.runOnIdle { assertTrue(repository.changes.isEmpty()) }
            rule.onNodeWithContentDescription("投稿メニュー").performClick()
            rule.onNodeWithText("ミュート（解除）").performClick()
            rule.onNodeWithText("実行").performClick()
            rule.waitUntil(5_000) { repository.changes.size == 1 }
            rule.onNodeWithContentDescription("投稿メニュー").performClick()
            rule.onNodeWithText("ミュート").assertIsEnabled()
            rule.onNodeWithText("ブロック（解除）").performClick()
            rule.onNodeWithText("実行").performClick()
            rule.waitUntil(5_000) { repository.changes.size == 2 }
            rule.onNodeWithContentDescription("投稿メニュー").performClick()
            rule.onNodeWithText("ブロック").assertIsEnabled()
            rule.onNodeWithText("報告").performClick()
            rule.onNodeWithText("送信").assertIsNotEnabled()
            rule.onNodeWithText("理由・補足").performTextInput("理由の入力")
            rule.onNodeWithText("送信").performClick()
            rule.waitUntil(5_000) { repository.reports.isNotEmpty() }
            rule.runOnIdle {
                assertEquals(listOf("mute" to false, "block" to false), repository.changes)
                assertEquals(listOf(Triple("author", "post", "理由の入力")), repository.reports)
            }
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun profileMenuDisablesUnknownStateAndRetriesBeforeUnblocking() {
        val repository = Repository().apply { failLookup = true }
        val store = ViewModelStore()
        lateinit var model: AccountProfileViewModel
        rule.runOnIdle { model = AccountProfileViewModel("author", repository, auth); store.put("profile", model) }
        try {
            rule.setContent {
                MastodonClientTheme(themeMode = ThemeMode.Dark) {
                    AccountProfileScreen(model, false, AppPreferences(), {}, {}, {}, { _, _ -> }, {}, {},
                        { _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("プロフィールのその他メニュー").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
            rule.onNodeWithText("ミュート").assertIsNotEnabled()
            rule.onNodeWithText("ブロック").assertIsNotEnabled()
            rule.runOnIdle { repository.failLookup = false }
            rule.onNodeWithText("関係状態を取得できませんでした・再試行").performClick()
            rule.onNodeWithText("ブロック（解除）").assertIsEnabled().performClick()
            rule.onNodeWithText("実行").performClick()
            rule.waitUntil(5_000) { repository.changes.isNotEmpty() }
            rule.runOnIdle { assertEquals(listOf("block" to false), repository.changes) }
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun mutingProfileStopsPaginationAndReopeningDoesNotShowAnotherLookup() {
        val repository = Repository().apply {
            relationship = AccountRelationship()
            paginatedPosts = true
            publishModeration = true
        }
        val store = ViewModelStore()
        lateinit var model: AccountProfileViewModel
        rule.runOnIdle { model = AccountProfileViewModel("author", repository, auth); store.put("profile", model) }
        try {
            rule.setContent {
                MastodonClientTheme(themeMode = ThemeMode.Dark) {
                    AccountProfileScreen(model, false, AppPreferences(), {}, {}, {}, { _, _ -> }, {}, {},
                        { _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
            rule.waitUntil(10_000) { model.uiState.value.profileTabs[ProfileStatusTab.Posts]?.isLoaded == true }
            rule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
            rule.onNodeWithText("関係状態を確認中").assertDoesNotExist()
            rule.onNodeWithText("ミュート").performClick()
            rule.onNodeWithText("実行").performClick()
            rule.waitUntil(5_000) { model.uiState.value.relationship?.muting == true &&
                model.uiState.value.profileTabs.values.all { !it.isLoading && !it.isLoadingMore && it.endReached } }
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
                .assertCountEquals(0)
            rule.onAllNodesWithText("対象の投稿").assertCountEquals(0)
            var calls = 0
            rule.runOnIdle { calls = repository.profilePageCalls; model.loadMore(); model.prepareTab(ProfileStatusTab.Media) }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(calls, repository.profilePageCalls) }
            rule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
            rule.onNodeWithText("関係状態を確認中").assertDoesNotExist()
            rule.onNodeWithText("ミュート（解除）").assertIsEnabled()
        } finally { rule.runOnIdle { store.clear() } }
    }

    private fun snapshot(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), name)
        file.outputStream().use { rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
