package io.github.ponpokoo.mastodonclient

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.ExploreFeed
import io.github.ponpokoo.mastodonclient.domain.model.ExplorePage
import io.github.ponpokoo.mastodonclient.feature.search.ExploreUiState
import io.github.ponpokoo.mastodonclient.feature.search.ExploreTabState
import androidx.compose.ui.test.onNodeWithText
import io.github.ponpokoo.mastodonclient.feature.search.SearchBar
import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.SearchContent
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
