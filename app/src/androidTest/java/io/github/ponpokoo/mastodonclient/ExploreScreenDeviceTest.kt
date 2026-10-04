package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.search.*
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagHeader
import io.github.ponpokoo.mastodonclient.feature.timeline.ExploreContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExploreScreenDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val tag = SearchTag("写真", "https://example.test/tags/photo", 456, true)

    @Test fun swipeOpenTrendTagAndUnfollowOnlyAfterConfirmation() {
        val state = mutableStateOf(ExploreUiState(tabs = mapOf(
            ExploreFeed.Hashtags to ExploreTabState(ExplorePage(tags = listOf(tag))),
            ExploreFeed.Followed to ExploreTabState(ExplorePage(tags = listOf(tag))),
        )))
        val selections = mutableListOf<ExploreFeed>()
        val openings = mutableListOf<Pair<String, Boolean>>()
        var unfollows = 0
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
            ExploreContent(state.value, { selections += it; state.value = state.value.copy(selectedFeed = it) },
                {}, {}, {}, { name, trend -> openings += name to trend }, {}, { unfollows++ }, {}, modifier = Modifier.fillMaxSize())
        } } }
        rule.onNodeWithTag("explore_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("explore_tab_hashtags").assertIsSelected()
        rule.onNodeWithText("#写真").performClick()
        rule.runOnIdle { assertEquals(listOf("写真" to true), openings) }
        screenshot("explore-hashtags")
        rule.onNodeWithTag("explore_tab_followed").performClick()
        rule.onNodeWithTag("unfollow_写真").performClick()
        rule.runOnIdle { assertEquals(0, unfollows) }
        rule.onNodeWithText("キャンセル").performClick()
        rule.runOnIdle { assertEquals(0, unfollows) }
        rule.onNodeWithTag("unfollow_写真").performClick()
        rule.onNodeWithText("解除する").performClick()
        rule.runOnIdle { assertEquals(1, unfollows) }
        rule.onNodeWithText("#写真").performClick()
        rule.runOnIdle {
            assertEquals("写真" to false, openings.last())
            assertEquals(listOf(ExploreFeed.Hashtags, ExploreFeed.Followed), selections)
        }
        screenshot("explore-followed")
    }

    @Test fun newsWithoutThumbnailOpensLinkAndPaginationRetryIsAvailable() {
        val url = "https://news.example/article"
        val state = ExploreUiState(selectedFeed = ExploreFeed.News, tabs = mapOf(ExploreFeed.News to ExploreTabState(
            ExplorePage(news = listOf(ExploreNews(url, "街の図書館で週末の読書イベント", "地域のニュースを紹介します", "Culture Journal", null)), nextCursor = "20"),
            error = "接続できませんでした", errorIsPagination = true,
        )))
        var opened: String? = null
        var retries = 0
        rule.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            ExploreContent(state, {}, {}, {}, { retries++ }, { _, _ -> }, { opened = it }, {}, {}, modifier = Modifier.fillMaxSize())
        } } }
        rule.onNodeWithText("街の図書館で週末の読書イベント").performClick()
        rule.onNodeWithText("再試行").performClick()
        rule.runOnIdle { assertEquals(url, opened); assertEquals(1, retries) }
        screenshot("explore-news")
    }

    @Test fun ordinaryTagUsesMenuAndTrendUsesButtonWithConfirmedUnfollow() {
        val trend = mutableStateOf(false)
        val following = mutableStateOf(false)
        var calls = 0
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Column {
            HashtagHeader("今月描いた絵を晒そう", trend.value, following.value, false, {},
                { calls++; following.value = !following.value }, {})
        } } }
        rule.onNodeWithTag("tag_subscription").assertDoesNotExist()
        rule.onNodeWithTag("tag_menu").performClick()
        rule.onNodeWithText("購読する").performClick()
        rule.runOnIdle { assertEquals(1, calls); assertTrue(following.value); trend.value = true }
        rule.onNodeWithTag("tag_menu").assertDoesNotExist()
        rule.onNodeWithTag("tag_subscription").performClick()
        rule.runOnIdle { assertEquals(1, calls) }
        rule.onNodeWithText("解除する").performClick()
        rule.runOnIdle { assertEquals(2, calls); assertFalse(following.value) }
        screenshot("trend-tag-header")
    }

    private fun screenshot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
