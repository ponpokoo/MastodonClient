package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReactionApi
import io.github.ponpokoo.mastodonclient.domain.model.ServerCapabilities
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.LocalServerCapabilities
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeReactionPickerDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeUsesStandardSheetMotionAndOtherStatusCardsStayFixed() {
        val home = mutableStateOf(true)
        var selection: String? = null
        val status = TimelineStatus("post", "post", "2026-10-22T00:00:00Z",
            StatusAuthor("author", "Author", "author@example.test", ""), null,
            "<p>本文</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        rule.setContent {
            MastodonClientTheme {
                CompositionLocalProvider(LocalServerCapabilities provides
                    ServerCapabilities(reactionApi = EmojiReactionApi.Fedibird)) {
                    if (home.value) {
                        TimelineContent(
                            state = TimelineUiState(statuses = listOf(status), isInitialLoading = false),
                            padding = PaddingValues(), listState = rememberLazyListState(),
                            onRefresh = {}, onRetry = {}, onLoadMore = {}, onStatusClick = {},
                            onAccountClick = {}, onMediaClick = { _, _ -> }, onOpenLink = {},
                            onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                            onBookmark = {}, onReact = { _, emoji -> selection = emoji },
                            onVotePoll = { _, _ -> }, onMoreClick = {}, onUnavailableAction = {},
                            preferences = AppPreferences(),
                        )
                    } else {
                        StatusCard(status = status, onStatusClick = null, onReact = { selection = it },
                            onUnavailableAction = {})
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("リアクション").performClick()
        val sheet = rule.onNodeWithTag("reaction_picker_sheet")
        val header = rule.onNodeWithTag("reaction_picker_header")
        val list = rule.onNodeWithTag("reaction_emoji_list")
        val initialTop = sheet.fetchSemanticsNode().boundsInWindow.top
        header.performTouchInput {
            swipe(start = center, end = Offset(centerX, centerY - height * 6), durationMillis = 350)
        }
        val expandedTop = sheet.fetchSemanticsNode().boundsInWindow.top
        assertTrue("Home picker should expand from its initial position", expandedTop < initialTop - 1f)
        list.performTouchInput { swipeDown(durationMillis = 500) }
        sheet.assertDoesNotExist()
        rule.runOnIdle { assertEquals(null, selection) }

        // Reopening still allows category selection and sends the selected reaction.
        rule.onNodeWithContentDescription("リアクション").performClick()
        rule.onNodeWithContentDescription("カテゴリ：標準").performClick()
        rule.onNodeWithText("👍").performClick()
        sheet.assertDoesNotExist()
        rule.runOnIdle { assertEquals("👍", selection); home.value = false }

        rule.onNodeWithContentDescription("リアクション").performClick()
        val otherTop = sheet.fetchSemanticsNode().boundsInWindow.top
        list.performTouchInput { swipeDown() }
        assertEquals(otherTop, sheet.fetchSemanticsNode().boundsInWindow.top, 1f)
        header.performTouchInput {
            swipe(start = center, end = Offset(centerX, centerY - height * 2), durationMillis = 350)
        }
        assertEquals(otherTop, sheet.fetchSemanticsNode().boundsInWindow.top, 1f)
    }
}
