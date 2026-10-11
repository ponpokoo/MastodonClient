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
        val syncs = mutableListOf<Pair<String, Set<ServerSetting>>>()
        override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> {
            syncs += session.sessionId to items
            return Result.success(Unit)
        }
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun getModerationAccounts(session: AccountSession, kind: ModerationListKind, maxId: String?) =
            Result.success(ModerationAccountsPage(listOf(ModerationAccount(author, relation)), null))
        override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
            relation = relation.copy(muting = muted); return Result.success(relation)
        }
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
            rule.onNodeWithText("ワードミュート", useUnmergedTree = true).assertDoesNotExist()
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
