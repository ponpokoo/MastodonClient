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
import io.github.ponpokoo.mastodonclient.feature.common.matchesWordMute
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
    private class Words : WordMuteRepository {
        override val words = MutableStateFlow<Map<String, List<String>>>(mapOf("A" to listOf("ネタバレ")))
        override suspend fun add(sessionId: String, word: String) { words.value = words.value + (sessionId to (words.value[sessionId].orEmpty() + word)) }
        override suspend fun remove(sessionId: String, word: String) { words.value = words.value + (sessionId to (words.value[sessionId].orEmpty() - word)) }
        override suspend fun restore(sessionId: String, word: String, index: Int) { words.value = words.value + (sessionId to (words.value[sessionId].orEmpty() + word)) }
    }
    private inner class Repository : TimelineRepository {
        var relation = AccountRelationship(muting = true, blocking = true)
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun getModerationAccounts(session: AccountSession, kind: ModerationListKind, maxId: String?) =
            Result.success(ModerationAccountsPage(listOf(ModerationAccount(author, relation)), null))
        override suspend fun setMuted(session: AccountSession, accountId: String, muted: Boolean): Result<AccountRelationship> {
            relation = relation.copy(muting = muted); return Result.success(relation)
        }
    }
    @Test fun releaseUndoAndTimedSnackbarAndAccountPickerWorkInBothThemes() {
        val repository = Repository(); val words = Words(); val store = ViewModelStore()
        lateinit var model: ModerationManagementViewModel
        rule.runOnIdle { model = ModerationManagementViewModel(repository, auth, words); store.put("management", model) }
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
            rule.onNodeWithText("ミュート中", useUnmergedTree = true).performClick()
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
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithText("ワードミュート", useUnmergedTree = true).performClick()
            rule.onNodeWithContentDescription("ネタバレ を削除").performClick()
            rule.onNodeWithText("削除", useUnmergedTree = true).performClick()
            rule.onNodeWithText("取り消し").assertIsDisplayed()
            rule.waitUntil(timeoutMillis = 4_000) { rule.onAllNodesWithText("取り消し").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("登録したワードはありません").assertIsDisplayed()
            rule.onNodeWithTag("management_account_switcher").performClick()
            rule.onNodeWithText("別アカウント").performClick()
            rule.onNodeWithText("@me · two.example").assertIsDisplayed()
        } finally { rule.runOnIdle { store.clear() } }
    }
    private fun capture(name: String) {
        val file = java.io.File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun wordMatcherUsesVisibleHtmlAndCwWithoutMatchingMarkupAttributes() {
        val post = TimelineStatus("row", "post", "", author, null,
            "<p><a href=\"https://example.com/spoiler\">Spo<b>il</b>er &amp; &#x79D8;密</a></p>", "CW注意", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        assertTrue(post.matchesWordMute(listOf("spoiler & 秘密")))
        assertTrue(post.matchesWordMute(listOf("cw注意")))
        assertFalse(post.matchesWordMute(listOf("https://example.com")))
        assertFalse(post.matchesWordMute(listOf(" ")))
    }
}
