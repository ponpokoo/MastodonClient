package io.github.ponpokoo.mastodonclient

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed
import io.github.ponpokoo.mastodonclient.domain.model.ExplorePage
import io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState
import io.github.ponpokoo.mastodonclient.feature.search.ExploreTabState
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.ponpokoo.mastodonclient.feature.search.SearchBar
import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.SearchContent
import io.github.ponpokoo.mastodonclient.feature.timeline.ReactionPickerSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchInputDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val query = mutableStateOf("")
    private val active = mutableStateOf(false)
    private val sessionKey = mutableStateOf("one")
    private var searches = 0

    @Test fun clearVisibilityBackRetentionAndKeyboardSearch() {
        showBar()
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_input").assertIsFocused()
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
        rule.onNodeWithTag("search_input").performTextInput("こんにちは")
        waitForKeyboard(true)
        rule.runOnIdle { assertEquals(0, searches) }
        rule.onNodeWithTag("search_input").performImeAction()
        rule.onNodeWithTag("search_input").assertIsNotFocused()
        waitForKeyboard(false)
        rule.runOnIdle { assertEquals(1, searches) }
        rule.onNodeWithTag("search_back").performClick()
        rule.onNodeWithTag("search_input").assertTextEquals("こんにちは")
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_clear").performClick()
        assertInputEmpty()
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
        rule.runOnIdle { assertFalse(active.value); assertEquals("", query.value) }
    }

    @Test fun clearDiscardsCompositionAndLateImeCommit() {
        showBar()
        rule.onNodeWithTag("search_open").performClick()
        var connection: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection = rule.activity.currentFocus?.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection!!.setComposingText("へんかんちゅう", 1)
        }
        rule.waitUntil(5_000) { query.value == "へんかんちゅう" }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection!!.performEditorAction(EditorInfo.IME_ACTION_SEARCH) }
        rule.runOnIdle { assertEquals(0, searches) }
        rule.onNodeWithTag("search_clear").performClick()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection!!.commitText("変換中", 1)
            connection!!.finishComposingText()
        }
        assertInputEmpty()
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
        rule.runOnIdle { assertEquals("", query.value); assertFalse(active.value) }
        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_input").performTextInput("新しい検索")
        rule.onNodeWithTag("search_input").assertTextEquals("新しい検索")
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun emojiClearDiscardsUnconfirmedTextAndKeepsEditing() {
        rule.setContent { MaterialTheme {
            ReactionPickerSheet(canUndo = false, onDismiss = {}, onSelected = {}, customEmojisOverride = emptyList())
        } }
        rule.onNodeWithTag("reaction_search").performClick()
        var connection: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // The bottom sheet owns a dialog window, separate from the activity's focus.
            connection = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                ?.findFocus()?.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection!!.setComposingText("へんかんちゅう", 1)
        }
        rule.onNodeWithTag("reaction_search").assertTextEquals("へんかんちゅう")
        rule.onNodeWithTag("reaction_search_clear").performClick()
        rule.onNodeWithTag("reaction_search").assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        rule.onNodeWithTag("reaction_search_clear").assertDoesNotExist()
        rule.onNodeWithTag("reaction_search").performTextInput("笑顔")
        rule.onNodeWithTag("reaction_search").assertTextEquals("笑顔")
        rule.onNodeWithTag("reaction_search_clear").performClick()
        rule.onNodeWithText("😊").assertExists()
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun emojiSheetLeavesBackgroundAboveTheKeyboardAndRestoresItsHeight() {
        rule.setContent { MaterialTheme {
            ReactionPickerSheet(canUndo = true, onDismiss = {}, onSelected = {}, customEmojisOverride = emptyList())
        } }
        val sheet = rule.onNodeWithTag("reaction_picker_sheet")
        val original = sheet.fetchSemanticsNode().boundsInWindow
        rule.onNodeWithTag("reaction_search").performTextInput("笑顔")
        fun waitForPickerKeyboard(visible: Boolean) {
            rule.waitUntil(5_000) {
                var matches = false
                rule.runOnUiThread {
                    val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                    matches = root?.let { ViewCompat.getRootWindowInsets(it) }
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
                }
                matches
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
            rule.waitForIdle()
        }
        waitForPickerKeyboard(true)
        rule.waitForIdle()
        var keyboardTop = 0f
        var contentTop = 0f
        rule.runOnUiThread {
            val root = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
            val location = IntArray(2)
            root.getLocationInWindow(location)
            keyboardTop = (location[1] + root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).toFloat()
            contentTop = (location[1] + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top).toFloat()
        }
        val withKeyboard = sheet.fetchSemanticsNode().boundsInWindow
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "reaction-picker-previews").apply { mkdirs() }
        java.io.File(directory, "keyboard.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        assertTrue("Sheet $withKeyboard should leave background below status bar bottom $contentTop",
            withKeyboard.top > contentTop)
        assertTrue("Sheet $withKeyboard should stay above keyboard top $keyboardTop (content top $contentTop)",
            withKeyboard.bottom <= keyboardTop + 1f)
        assertTrue("Emoji list should shrink for the keyboard", withKeyboard.height < original.height)
        assertTrue(rule.onNodeWithTag("reaction_emoji_list").fetchSemanticsNode().boundsInWindow.height > 0f)
        rule.onNodeWithContentDescription("カテゴリ：履歴").performClick()
        val emptyCategory = sheet.fetchSemanticsNode().boundsInWindow
        assertEquals(withKeyboard.top, emptyCategory.top, 1f)
        assertEquals(withKeyboard.height, emptyCategory.height, 1f)
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        waitForPickerKeyboard(false)
        assertEquals(original.height, sheet.fetchSemanticsNode().boundsInWindow.height, 1f)
    }

    @Test fun searchTargetsSwitchWithTabsAndSwipes() {
        val state = mutableStateOf(SearchUiState(searchQuery = "hello", isSearchActive = true))
        val selections = mutableListOf<SearchTarget>()
        rule.setContent {
            MaterialTheme {
                SearchContent(state = state.value, padding = PaddingValues(), onQueryChanged = {}, onSearch = {},
                    onEnterSearch = {}, onBack = {}, onClear = {}, onSelectTarget = {
                        selections += it; state.value = state.value.copy(selectedTarget = it)
                    }, onLoadMore = {}, onRetry = {}, onStatusClick = {}, onOpenLink = {}, onReply = {},
                    onBoost = {}, onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                    onAccountClick = {}, onMediaClick = { _, _ -> }, onMoreClick = {})
            }
        }
        rule.onNodeWithTag("search_target_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("search_target_accounts").assertIsSelected()
        rule.onNodeWithTag("search_target_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("search_target_hashtags").assertIsSelected()
        rule.onNodeWithTag("search_target_pager").performTouchInput { swipeRight() }
        rule.onNodeWithTag("search_target_accounts").assertIsSelected()
        rule.onNodeWithTag("search_target_posts").performClick()
        rule.onNodeWithTag("search_target_posts").assertIsSelected()
        rule.runOnIdle { assertEquals(listOf(SearchTarget.Accounts, SearchTarget.Hashtags, SearchTarget.Accounts, SearchTarget.Posts), selections) }
    }

    @Test fun accountChangeDiscardsPreviousEditorsComposition() {
        showBar()
        rule.onNodeWithTag("search_open").performClick()
        var connection: InputConnection? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            connection = rule.activity.currentFocus?.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection!!.setComposingText("前のアカウント", 1)
        }
        rule.waitUntil(5_000) { query.value == "前のアカウント" }
        rule.runOnIdle { sessionKey.value = "two"; query.value = ""; active.value = false }
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { connection!!.commitText("古い入力", 1) }
        assertInputEmpty()
        rule.runOnIdle { assertEquals("", query.value) }
        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_input").performTextInput("新しいアカウント")
        rule.onNodeWithTag("search_input").assertTextEquals("新しいアカウント")
    }

    private fun showBar() {
        rule.setContent { MaterialTheme { SearchBar(
            query = query.value, active = active.value, sessionKey = sessionKey.value,
            onQueryChanged = { query.value = it }, onEnterSearch = { active.value = true },
            onBack = { active.value = false }, onClear = { query.value = ""; active.value = false },
            onSearch = { searches++ },
        ) } }
    }

    @Test fun emptySubscriptionsCanStartHashtagSearchWithFocusedInput() {
        val state = mutableStateOf(SearchUiState())
        rule.setContent { MaterialTheme { SearchContent(state.value, PaddingValues(),
            onQueryChanged = { state.value = state.value.copy(searchQuery = it) }, onSearch = { searches++ },
            onEnterSearch = { state.value = state.value.copy(isSearchActive = true) },
            onBack = { state.value = state.value.copy(isSearchActive = false) }, onClear = {},
            onSelectTarget = { state.value = state.value.copy(selectedTarget = it) }, onLoadMore = {}, onRetry = {},
            onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
            onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
            onMoreClick = {},
            exploreState = ExploreUiState(selectedFeed = ExploreFeed.Followed,
                tabs = mapOf(ExploreFeed.Followed to ExploreTabState(page = ExplorePage()))),
        ) } }
        rule.onNodeWithText("タグを検索").performClick()
        rule.onNodeWithTag("search_input").assertIsFocused().performTextInput("写真")
        waitForKeyboard(true)
        rule.onNodeWithTag("search_target_hashtags").assertIsSelected()
        rule.onNodeWithTag("search_input").performImeAction()
        rule.onNodeWithTag("search_input").assertIsNotFocused()
        waitForKeyboard(false)
        rule.runOnIdle { assertEquals(1, searches); assertEquals(SearchTarget.Hashtags, state.value.selectedTarget) }
        rule.onNodeWithTag("search_back").performClick()
        rule.onNodeWithText("タグを検索").performClick()
        rule.onNodeWithTag("search_input").assertIsFocused().performTextInput("館")
        waitForKeyboard(true)
        rule.runOnIdle { assertEquals("写真館", state.value.searchQuery) }
    }

    private fun waitForKeyboard(visible: Boolean) {
        rule.waitUntil(5_000) {
            var observed = false
            rule.runOnUiThread {
                observed = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            observed
        }
    }

    private fun assertInputEmpty() {
        rule.onNodeWithTag("search_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
