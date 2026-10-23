package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import io.github.ponpokoo.mastodonclient.navigation.animatedScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModerationManagementDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("A", "https://one.example", "me", "me", "管理アカウント", "", "unused")
    private val second = session.copy(sessionId = "B", displayName = "別アカウント", instanceUrl = "https://two.example")
    private val author = StatusAuthor("target", "対象ユーザー", "target@one.example", "")
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = session
        override suspend fun getSessions() = listOf(session, second)
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
    }
    private inner class Repository : TimelineRepository {
        override val serverSettings = MutableStateFlow(listOf(session, second).associate { account -> account.sessionId to ServerAccountSettings(account.instanceUrl, account.accountId,
            mutes = listOf(ModerationAccount(author, AccountRelationship(muting = true, blocking = true))), blocks = listOf(ModerationAccount(author, AccountRelationship(muting = true, blocking = true))),
            fetch = mapOf(ServerSetting.Mutes to SettingFetch(fetchedAt = 1), ServerSetting.Blocks to SettingFetch(fetchedAt = 1))) })
        var relation = AccountRelationship(muting = true, blocking = true)
        var onSync: suspend (AccountSession, Set<ServerSetting>) -> Unit = { _, _ -> }
        var onDeleteFilter: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        val syncs = mutableListOf<Pair<String, Set<ServerSetting>>>()
        override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> {
            syncs += session.sessionId to items
            onSync(session, items)
            return Result.success(Unit)
        }
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun getModerationAccounts(session: AccountSession, kind: ModerationListKind, maxId: String?) =
            Result.success(ModerationAccountsPage(listOf(ModerationAccount(author, relation)), null))
        override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
            relation = relation.copy(muting = muted); return Result.success(relation)
        }
        override suspend fun deleteServerFilter(session: AccountSession, id: String) = onDeleteFilter(id)
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun internalBackStaysInManagementAndOverviewUsesPredictiveNavigation() {
        val store = ViewModelStore()
        lateinit var model: ModerationManagementViewModel
        lateinit var navigation: androidx.navigation.NavHostController
        rule.runOnIdle { model = ModerationManagementViewModel(Repository(), auth); store.put("back", model) }
        try {
            rule.setContent {
                MastodonClientTheme {
                    navigation = androidx.navigation.compose.rememberNavController()
                    io.github.ponpokoo.mastodonclient.navigation.ScreenNavHost(navigation, "settings") {
                        animatedScreen("settings") { androidx.compose.material3.Text("設定トップ") }
                        animatedScreen("management") {
                            ModerationManagementScreen(model) { navigation.popBackStack() }
                        }
                    }
                }
            }
            rule.runOnIdle { navigation.navigate("management"); model.open(ModerationPage.Words) }
            rule.waitForIdle()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(ModerationPage.Overview, model.state.value.page); assertEquals("management", navigation.currentDestination?.route) }
            val original = rule.onNodeWithTag("moderation_management_content").fetchSemanticsNode().boundsInRoot
            rule.mainClock.autoAdvance = false
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT)) }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(200f, 400f, 0.6f, androidx.activity.BackEventCompat.EDGE_LEFT)) }
            rule.mainClock.advanceTimeBy(64)
            rule.waitForIdle()
            assertTrue("Overview intercepted predictive navigation", rule.onNodeWithTag("moderation_management_content").fetchSemanticsNode().boundsInRoot.left > original.left)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("management", navigation.currentDestination?.route) }
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithText("設定トップ").assertIsDisplayed()
        } finally { rule.mainClock.autoAdvance = true; rule.runOnIdle { store.clear() } }
    }
    @Test fun refreshAndServerSyncKeepOneContentSpinnerThroughAllPhases() {
        val repository = Repository()
        val store = ViewModelStore()
        var finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var model: ModerationManagementViewModel
        rule.runOnIdle { model = ModerationManagementViewModel(repository, auth); store.put("management", model) }
        repository.onSync = { _, _ -> finish.await() }
        fun updateLoading(items: Set<ServerSetting>, loading: Boolean) {
            val saved = repository.serverSettings.value.getValue(session.sessionId)
            repository.serverSettings.value = repository.serverSettings.value + (session.sessionId to saved.copy(
                fetch = saved.fetch + items.associateWith { SettingFetch(loading = loading, fetchedAt = 1) }))
        }
        try {
            rule.setContent { MastodonClientTheme { ModerationManagementScreen(model, {}) } }
            val progress = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            for (page in listOf(ModerationPage.Mutes, ModerationPage.Blocks, ModerationPage.Filters)) {
                val items = if (page == ModerationPage.Filters) setOf(ServerSetting.Filters)
                    else setOf(ServerSetting.Mutes, ServerSetting.Blocks)
                rule.runOnIdle { finish = kotlinx.coroutines.CompletableDeferred(); model.open(page); model.refresh() }
                rule.onAllNodes(progress).assertCountEquals(1)
                val spinner = rule.onNodeWithTag("management_processing")
                spinner.assertIsDisplayed().assert(hasAnyAncestor(hasTestTag("moderation_management_content")))
                val spinnerId = spinner.fetchSemanticsNode().id
                rule.runOnIdle { updateLoading(items, true) }
                rule.waitUntil(5_000) {
                    model.state.value.server?.fetch?.filterKeys { it in items }?.values?.all { it.loading } == true
                }
                rule.onAllNodes(progress).assertCountEquals(1)
                assertEquals(spinnerId, spinner.fetchSemanticsNode().id)
                rule.runOnIdle { finish.complete(Unit) }
                rule.waitUntil(5_000) { !model.state.value.busy }
                rule.onAllNodes(progress).assertCountEquals(1)
                assertEquals(spinnerId, spinner.fetchSemanticsNode().id)
                rule.runOnIdle { updateLoading(items, false) }
                rule.onAllNodes(progress).assertCountEquals(0)
            }
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun filterDeletionProgressRemainsVisibleAfterScrollingAndStopsOnFailureAndRetry() {
        val repository = Repository()
        val filters = List(30) { ServerFilter("filter-$it", "フィルター$it") }
        val saved = repository.serverSettings.value.getValue(session.sessionId)
        repository.serverSettings.value = repository.serverSettings.value + (session.sessionId to saved.copy(filters = filters))
        var finish = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        repository.onDeleteFilter = { finish.await() }
        val store = ViewModelStore()
        lateinit var model: ModerationManagementViewModel
        rule.runOnIdle { model = ModerationManagementViewModel(repository, auth); store.put("management", model) }
        try {
            rule.setContent { MastodonClientTheme { ModerationManagementScreen(model, {}) } }
            rule.runOnIdle { model.open(ModerationPage.Filters) }
            rule.onNodeWithTag("moderation_management").performScrollToNode(hasText(filters.last().title))
            rule.onNodeWithText(filters.last().title).assertIsDisplayed()
            rule.runOnIdle { model.deleteFilter(filters.last()) }
            val progress = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            rule.onNode(progress).assertIsDisplayed()
            rule.runOnIdle { finish.complete(Result.failure(IllegalStateException("削除失敗"))) }
            rule.waitUntil(5_000) { !model.state.value.busy && model.state.value.error != null }
            rule.onAllNodes(progress).assertCountEquals(0)
            rule.runOnIdle { finish = kotlinx.coroutines.CompletableDeferred(); model.retry() }
            rule.onNode(progress).assertIsDisplayed()
            rule.runOnIdle { finish.complete(Result.success(Unit)) }
            rule.waitUntil(5_000) { !model.state.value.busy && model.state.value.error == null }
            rule.onAllNodes(progress).assertCountEquals(0)
        } finally { rule.runOnIdle { store.clear() } }
    }

    @Test fun releaseUndoAndTimedSnackbarAndAccountPickerWorkInBothThemes() {
        val repository = Repository(); val store = ViewModelStore()
        lateinit var model: ModerationManagementViewModel
        rule.runOnIdle { model = ModerationManagementViewModel(repository, auth); store.put("management", model) }
        val preferences = UserPreferencesStore(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = transform(data.value).also { data.value = it }
        })
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        }).also { store.put("maintenance", it) }
        val theme = androidx.compose.runtime.mutableStateOf(ThemeMode.Dark)
        try {
            rule.setContent { MastodonClientTheme(themeMode = theme.value) {
                SettingsScreen(preferences, session, listOf(session, second), maintenance, {}, {}, {}, moderation = model)
            } }
            rule.onNodeWithTag("settings_category_Moderation").performScrollTo().performClick()
            rule.onNodeWithTag("management_account_switcher").assertIsDisplayed()
            rule.onNodeWithContentDescription("一覧を更新").assertDoesNotExist()
            rule.onNodeWithText("端末内のワードミュート", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithText("ミュート中", useUnmergedTree = true).performClick()
            rule.onNodeWithTag("management_account_switcher").assertDoesNotExist()
            rule.onNodeWithContentDescription("一覧を更新").performClick()
            rule.runOnIdle { assertEquals(session.sessionId to setOf(ServerSetting.Mutes, ServerSetting.Blocks), repository.syncs.last()) }
            capture("management-dark")
            rule.onNodeWithText("解除", useUnmergedTree = true).performClick()
            rule.onNodeWithText("ブロックは引き続き有効です。投稿の非表示も続きます。").assertIsDisplayed()
            rule.onNodeWithText("キャンセル").performClick()
            assertTrue(repository.relation.muting)
            rule.onNodeWithText("解除", useUnmergedTree = true).performClick()
            rule.onNode(hasText("解除") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
            rule.onNodeWithText("取り消し").performClick()
            rule.runOnIdle { assertTrue(repository.relation.muting); assertTrue(repository.relation.blocking) }
            rule.onNodeWithText("解除", useUnmergedTree = true).assertIsDisplayed()
            rule.runOnIdle { theme.value = ThemeMode.Light }
            capture("management-light")
            rule.onNodeWithText("解除", useUnmergedTree = true).performClick()
            rule.onNode(hasText("解除") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
            rule.onNodeWithText("取り消し").assertIsDisplayed()
            rule.waitUntil(timeoutMillis = 4_000) { rule.onAllNodesWithText("取り消し").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithTag("management_account_switcher").performClick()
            rule.onNodeWithText("別アカウント").performClick()
            rule.onNodeWithText("@me · two.example").assertIsDisplayed()
            listOf("ブロック中", "サーバーのフィルター").forEach { label ->
                rule.onNodeWithText(label, useUnmergedTree = true).performClick()
                rule.onNodeWithTag("management_account_switcher").assertDoesNotExist()
                rule.onNodeWithContentDescription("一覧を更新").performClick()
                rule.runOnIdle {
                    val items = if (label == "サーバーのフィルター") setOf(ServerSetting.Filters) else setOf(ServerSetting.Mutes, ServerSetting.Blocks)
                    assertEquals(second.sessionId to items, repository.syncs.last())
                }
                rule.runOnIdle { assertEquals(second.sessionId, model.state.value.selected?.sessionId) }
                rule.onNodeWithContentDescription("戻る").performClick()
                rule.onNodeWithText("@me · two.example").assertIsDisplayed()
            }
        } finally { rule.runOnIdle { store.clear() } }
    }
    private fun capture(name: String) {
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
