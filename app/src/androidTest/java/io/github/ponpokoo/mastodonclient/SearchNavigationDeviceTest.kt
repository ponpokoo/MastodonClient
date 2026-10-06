package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ponpokoo.mastodonclient.domain.model.SearchResults
import io.github.ponpokoo.mastodonclient.domain.model.SearchTarget
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.search.SearchTabState
import io.github.ponpokoo.mastodonclient.feature.search.SearchUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.SearchContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun detailReturnPreservesResultsAndPositionWithoutKeyboardAndAllowsReediting() {
        val state = mutableStateOf(SearchUiState(sessionKey = "search-navigation"))
        val results = SearchResults(emptyList(), List(30) { index ->
            TimelineStatus(
                timelineId = "row-$index", statusId = "post-$index", createdAt = "2026-10-06T00:00:00Z",
                author = StatusAuthor("author", "検索の投稿者", "author@example.test", ""),
                boostedBy = null, contentHtml = "<p>検索結果 $index</p>", spoilerText = "", sensitive = false,
                visibility = "public", url = null, repliesCount = 0, boostsCount = 0, favouritesCount = 0,
                mediaAttachments = emptyList(),
            )
        }, emptyList())
        var searches = 0
        var openedStatus: String? = null
        rule.setContent {
            MaterialTheme {
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "search") {
                    composable("search") {
                        SearchContent(
                            state = state.value, padding = PaddingValues(),
                            onQueryChanged = { state.value = state.value.copy(searchQuery = it) },
                            onSearch = {
                                searches++
                                state.value = state.value.copy(submittedQuery = state.value.searchQuery,
                                    tabs = mapOf(SearchTarget.Posts to SearchTabState(results = results, endReached = true)))
                            },
                            onEnterSearch = { state.value = state.value.copy(isSearchActive = true) },
                            onBack = { state.value = state.value.copy(isSearchActive = false) },
                            onClear = { state.value = SearchUiState(sessionKey = state.value.sessionKey) },
                            onSelectTarget = { state.value = state.value.copy(selectedTarget = it) },
                            onLoadMore = {}, onRetry = {},
                            onStatusClick = { openedStatus = it; navigation.navigate("detail") },
                            onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                            onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                        )
                    }
                    composable("detail") {
                        TextButton(onClick = { navigation.popBackStack() }, modifier = Modifier.testTag("detail_back")) {
                            Text("検索結果へ戻る")
                        }
                    }
                }
            }
        }

        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_input").performTextInput("検索語")
        waitForKeyboard(true)
        rule.onNodeWithTag("search_input").performImeAction()
        rule.onNodeWithTag("search_input").assertIsNotFocused()
        waitForKeyboard(false)
        rule.onNodeWithTag("search_results_posts").performScrollToNode(hasText("検索結果 12"))
        rule.onNodeWithText("検索結果 12").assertIsDisplayed()
        rule.onNode(hasTestTag("timeline_status") and hasText("検索結果 12")).performClick()
        rule.onNodeWithTag("search_input").assertDoesNotExist()
        rule.runOnIdle { assertEquals("post-12", openedStatus) }
        rule.onNodeWithTag("detail_back").performClick()

        rule.onNodeWithTag("search_input").assertTextEquals("検索語").assertIsNotFocused()
        rule.onNodeWithTag("search_target_posts").assertIsSelected()
        rule.onNodeWithText("検索結果 12").assertIsDisplayed()
        waitForKeyboard(false)
        rule.runOnIdle {
            assertTrue(state.value.isSearchActive)
            assertEquals(results, state.value.searchResults)
            assertEquals(1, searches)
        }

        rule.onNodeWithTag("search_input").performClick().assertIsFocused().performTextInput("を再編集")
        waitForKeyboard(true)
        rule.onNodeWithTag("search_input").performImeAction()
        rule.onNodeWithTag("search_input").assertIsNotFocused()
        waitForKeyboard(false)
        rule.runOnIdle { assertEquals("検索語を再編集", state.value.submittedQuery); assertEquals(2, searches) }
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
}
