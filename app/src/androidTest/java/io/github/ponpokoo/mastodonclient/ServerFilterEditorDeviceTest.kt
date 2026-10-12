package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.feature.common.matchesWordMute
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServerFilterEditorDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("A", "https://example.test", "me", "me", "管理アカウント", "", "unused")
    private val auth = object : AuthRepository {
        override suspend fun restoreSession() = session
        override suspend fun getSessions() = listOf(session)
        override suspend fun logout() = Unit
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
    }
    private inner class Repository(initial: List<ServerFilter> = emptyList()) : TimelineRepository {
        override val serverSettings = MutableStateFlow(mapOf(session.sessionId to ServerAccountSettings(session.instanceUrl, session.accountId, filters = initial)))
        val saves = mutableListOf<Pair<ServerFilter?, FilterEdit>>()
        var fail = false
        var gate: CompletableDeferred<Result<ServerFilter>>? = null
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) = Result.success(TimelinePage(emptyList(), null, true))
        override suspend fun saveServerFilter(session: AccountSession, original: ServerFilter?, edit: FilterEdit): Result<ServerFilter> {
            assertEquals(this@ServerFilterEditorDeviceTest.session, session)
            saves += original to edit
            val result = gate?.await() ?: if (fail) Result.failure(IllegalArgumentException("保存できませんでした")) else Result.success(
                ServerFilter(original?.id ?: "new", edit.title, edit.context, original?.expiresAt, edit.action, edit.keywords, original?.statuses.orEmpty()))
            result.onSuccess { saved -> serverSettings.value = serverSettings.value.mapValues { (_, settings) ->
                settings.copy(filters = settings.filters.orEmpty().filterNot { it.id == saved.id } + saved)
            } }
            return result
        }
    }
    private fun withScreen(repository: Repository, words: WordMuteRepository? = null,
        test: (ModerationManagementViewModel, androidx.compose.runtime.MutableState<ThemeMode>) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: ModerationManagementViewModel
        rule.runOnIdle { model = ModerationManagementViewModel(repository, auth, wordRepository = words); store.put("management", model); model.open(ModerationPage.Filters) }
        val theme = mutableStateOf(ThemeMode.Dark)
        try {
            rule.setContent { MastodonClientTheme(themeMode = theme.value) { ModerationManagementScreen(model, {}) } }
            rule.waitUntil(5_000) { model.state.value.selected != null && model.state.value.server != null }
            test(model, theme)
        } finally { rule.runOnIdle { store.clear() } }
    }
    @Test fun unsupportedAndFailuresDoNotShowEmptySuccessAndLocalWordMutesStillWork() {
        val repository = Repository()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        val data = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                transform(data.value).also { data.value = it }
        }
        val words = io.github.ponpokoo.mastodonclient.data.repository.DefaultWordMuteRepository(
            io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore(data), scope)
        try {
            withScreen(repository, words) { model, _ ->
                fun fetch(value: SettingFetch, filters: List<ServerFilter>? = emptyList()) {
                    rule.runOnIdle { repository.serverSettings.value = mapOf(session.sessionId to ServerAccountSettings(
                        session.instanceUrl, session.accountId, filters = filters, fetch = mapOf(ServerSetting.Filters to value))) }
                }
                val empty = "登録したフィルターはありません"
                val unsupported = "このサーバーは公式フィルターに対応していません。端末内のワードミュートをご利用ください。"
                fetch(SettingFetch(error = "通信失敗"), null)
                rule.onNodeWithText("通信失敗").assertIsDisplayed()
                rule.onNodeWithText(empty).assertDoesNotExist()
                rule.onNodeWithText(unsupported).assertDoesNotExist()
                fetch(SettingFetch(unsupported = true))
                rule.onNodeWithText(unsupported).assertIsDisplayed()
                rule.onNodeWithText(empty).assertDoesNotExist()
                rule.onNodeWithText("フィルターを追加").assertIsNotEnabled()
                capture("filter-unsupported")
                rule.onNodeWithText("端末内のワードミュート").performClick()
                rule.onNodeWithTag("word_mute_input").performTextInput("spoiler")
                rule.onNodeWithText("追加").performClick()
                rule.waitUntil(5_000) { model.state.value.words == listOf("spoiler") }
                capture("local-word-mute")
                val status = TimelineStatus(timelineId = "post", statusId = "post", createdAt = "2026-10-12T00:00:00Z",
                    author = StatusAuthor("author", "Author", "author", ""), boostedBy = null,
                    contentHtml = "<p>Hello <b>SPOILER</b> &amp; end</p>", spoilerText = "", sensitive = false,
                    visibility = "public", url = null, repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList())
                assertTrue(status.matchesWordMute(words.words.value[session.sessionId].orEmpty()))
                assertTrue(status.copy(contentHtml = "<p>safe</p>", spoilerText = "SPOILER warning").matchesWordMute(listOf("spoiler")))
                assertFalse(status.matchesWordMute(listOf("<b>")))
                rule.runOnIdle { model.open(ModerationPage.Filters) }
                fetch(SettingFetch(fetchedAt = 1))
                rule.onNodeWithText(empty).assertIsDisplayed()
                fetch(SettingFetch(fetchedAt = 1, error = "認証失敗"))
                rule.onNodeWithText("認証失敗").assertIsDisplayed()
                rule.onNodeWithText(empty).assertDoesNotExist()
                fetch(SettingFetch(fetchedAt = 1), listOf(ServerFilter("server", "登録済み", setOf("home"))))
                rule.onNodeWithText("登録済み").assertIsDisplayed()
                rule.onNodeWithText(empty).assertDoesNotExist()
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun addingKeywordsAndSavingFromBottomPreservesInputOnFailureAndPreventsDuplicateSaves() {
        val repository = Repository()
        withScreen(repository) { model, theme ->
            rule.onNodeWithText("フィルターを追加").performClick()
            rule.onNodeWithTag("filter_save").assertIsNotEnabled()
            rule.onNodeWithTag("filter_title").performTextInput("ネタバレ")
            rule.onNodeWithTag("filter_keyword_0").performTextInput("最終話")
            rule.onNodeWithText("単語全体に一致").performClick()
            rule.onNodeWithText("キーワードを追加").performScrollTo().performClick()
            rule.onNodeWithTag("filter_keyword_1").assertIsFocused()
            rule.onNodeWithTag("filter_save").assertIsNotEnabled()
            rule.onNodeWithTag("filter_keyword_1").performTextInput("結末")
            rule.onNodeWithTag("filter_save").assertIsDisplayed().assertIsEnabled()
            rule.onNodeWithText("キーワードを追加").performScrollTo().performClick()
            rule.onNodeWithTag("filter_keyword_2").assertIsFocused()
            rule.onNodeWithContentDescription("キーワード 3を削除").performClick()
            rule.onNodeWithTag("filter_keyword_1").performImeAction()
            rule.onNodeWithText("通知欄").performScrollTo().performClick()
            rule.onNodeWithText("公開タイムライン").performScrollTo().performClick()
            rule.onNodeWithText("非表示").performScrollTo().performClick()
            rule.onNodeWithTag("filter_expiry").performScrollTo().performClick()
            rule.onNodeWithText("1日").performClick()
            rule.onNodeWithTag("filter_save").assertIsDisplayed().assertIsEnabled()
            rule.runOnIdle { repository.fail = true }
            rule.onNodeWithTag("filter_save").performClick()
            rule.onNodeWithText("保存できませんでした").performScrollTo().assertIsDisplayed()
            rule.runOnIdle {
                val edit = model.state.value.editor!!.edit
                assertEquals(listOf(FilterKeyword(keyword = "最終話", wholeWord = true), FilterKeyword(keyword = "結末")), edit.keywords)
                assertEquals(setOf("home", "public"), edit.context)
                assertEquals("hide", edit.action); assertEquals(86400L, edit.expiresIn); assertTrue(edit.changeExpiry)
                assertEquals(1, repository.saves.size)
                theme.value = ThemeMode.Light
                repository.fail = false
                repository.gate = CompletableDeferred()
            }
            capture("filter-editor-light")
            rule.onNodeWithTag("filter_save").performClick()
            rule.onNodeWithTag("filter_save").assertIsNotEnabled()
            rule.onNodeWithContentDescription("戻る").assertIsNotEnabled()
            rule.runOnIdle {
                val edit = model.state.value.editor!!.edit
                assertEquals(2, repository.saves.size)
                repository.gate!!.complete(Result.success(ServerFilter("new", edit.title, edit.context, action = edit.action, keywords = edit.keywords)))
            }
            rule.waitUntil(5_000) { model.state.value.editor == null }
            rule.onNodeWithText("ネタバレ").assertIsDisplayed()
            rule.onNodeWithText("編集").assertIsDisplayed()
            rule.onNodeWithText("削除").assertIsDisplayed()
        }
    }
    @Test fun editingKeepsExpiryStatusesAndKeywordIdsAndBackReturnsToList() {
        val original = ServerFilter("existing", "既存のフィルター", setOf("home", "future"), "2026-12-01T00:00:00Z", "blur",
            listOf(FilterKeyword("keyword-id", "既存ワード", true)), listOf(FilterStatus("match-id", "status-id")))
        val repository = Repository(listOf(original))
        withScreen(repository) { model, _ ->
            rule.onNodeWithText("編集").performClick()
            rule.onNodeWithTag("filter_title").performTextReplacement("保存しない変更")
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithText("既存のフィルター").assertIsDisplayed()
            rule.runOnIdle { assertNull(model.state.value.editor); assertEquals(ModerationPage.Filters, model.state.value.page); assertTrue(repository.saves.isEmpty()) }
            rule.onNodeWithText("編集").performClick()
            rule.onNodeWithTag("filter_title").performTextReplacement("名前を変更")
            rule.onNodeWithTag("filter_title").performImeAction()
            capture("filter-editor-dark")
            rule.onNodeWithTag("filter_expiry").performScrollTo().performClick()
            rule.onNodeWithText("7日").performClick()
            rule.onNodeWithTag("filter_expiry").performClick()
            rule.onNodeWithText("変更しない").performClick()
            rule.onNodeWithTag("filter_save").assertIsDisplayed().performClick()
            rule.waitUntil(5_000) { model.state.value.editor == null }
            rule.runOnIdle {
                val (source, edit) = repository.saves.single()
                assertEquals(original, source); assertEquals("名前を変更", edit.title)
                assertEquals(original.context, edit.context); assertEquals("blur", edit.action)
                assertEquals(original.keywords, edit.keywords); assertFalse(edit.changeExpiry); assertNull(edit.expiresIn)
                assertEquals(original.statuses, model.state.value.server!!.filters!!.single().statuses)
            }
        }
    }
    @Test fun filterUpdateNoticeUsesTimedSnackbarAndDoesNotRemainInList() {
        val repository = Repository(listOf(ServerFilter("existing", "ネタバレ", setOf("home", "notifications"))))
        withScreen(repository) { _, _ ->
            rule.runOnIdle {
                repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(displayPending = true) }
            }
            val notice = "設定保存済み・投稿への反映待ちです"
            rule.onNode(hasText(notice) and hasAnyAncestor(hasTestTag("management_snackbar"))).assertIsDisplayed()
            capture("filter-list-update")
            rule.waitUntil(4_000) { rule.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("ネタバレ").assertIsDisplayed()
            rule.runOnIdle {
                repository.serverSettings.value = repository.serverSettings.value.mapValues { (_, saved) -> saved.copy(displayChecks = mapOf("home" to false)) }
            }
            rule.onNodeWithText(notice).assertDoesNotExist()
        }
    }

    @Test fun largeFontFormKeepsSelectionsAndSaveAccessibleOnNarrowWidth() {
        val editor = mutableStateOf(FilterEditorState(null, FilterEdit("ネタバレ", setOf("home", "notifications"), "warn", listOf(FilterKeyword(keyword = "最終話")))))
        val saved = java.util.concurrent.atomic.AtomicReference<FilterEdit>()
        rule.setContent {
            MastodonClientTheme(themeMode = ThemeMode.Dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        ServerFilterEditor(editor.value, false, null, { editor.value = editor.value.copy(edit = it) }, { saved.set(editor.value.edit) }, {})
                    }
                }
            }
        }
        rule.onNodeWithText("通知欄").performScrollTo().performClick()
        rule.onNodeWithText("非表示").performScrollTo().performClick()
        rule.onNodeWithTag("filter_expiry").performScrollTo().performClick()
        rule.onNodeWithText("7日").performClick()
        rule.onNodeWithTag("filter_save").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(setOf("home"), saved.get().context)
            assertEquals("hide", saved.get().action); assertEquals(604800L, saved.get().expiresIn)
        }
        rule.onNodeWithTag("filter_title").performScrollTo()
        capture("filter-editor-large-font")
    }
    private fun capture(name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = output?.let { java.io.File(it) } ?: rule.activity.getExternalFilesDir(null)!!
        directory.mkdirs()
        java.io.File(directory, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
