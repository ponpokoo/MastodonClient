package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.status.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatusActionsDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun omittedSocialActionsDisableButtonsAndLeavePollReadOnly() {
        rule.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SocialStatus(
                    status = post(), actions = StatusActions(),
                    onStatusClick = {}, onOpenLink = {}, onAccountClick = {}, onMediaClick = { _, _ -> },
                    onMoreClick = {}, preferences = preferences,
                )
            }
        } }
        for (label in listOf("返信", "ブースト", "お気に入り", "ブックマーク")) {
            rule.onNodeWithContentDescription(label).performScrollTo().assertIsNotEnabled()
        }
        rule.onNodeWithContentDescription("リアクション").assertDoesNotExist()
        rule.onNodeWithText("投票する").assertDoesNotExist()
        rule.onNodeWithText("first").assertExists()
    }

    @Test fun groupedHandlersReceiveTheCurrentOriginalPostAndPollChoices() {
        val current = mutableStateOf(post())
        val received = mutableListOf<Pair<String, TimelineStatus>>()
        var vote: Pair<TimelineStatus, Set<Int>>? = null
        rule.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatusCard(
                    status = current.value, onStatusClick = null, onUnavailableAction = {}, preferences = preferences,
                    actions = StatusActions(
                        onReply = { received += "reply" to it },
                        onFavourite = { received += "favourite" to it },
                        onBookmark = { received += "bookmark" to it },
                        onVotePoll = { status, choices -> vote = status to choices },
                    ),
                )
            }
        } }
        rule.runOnIdle { current.value = post().copy(timelineId = "boost-wrapper", contentHtml = "Updated") }
        for ((label, action) in listOf("返信" to "reply", "お気に入り" to "favourite", "ブックマーク" to "bookmark")) {
            rule.onNodeWithContentDescription(label).performScrollTo().performClick()
            rule.runOnIdle { assertEquals(action to current.value, received.last()) }
        }
        rule.onNodeWithText("second").performScrollTo().performClick()
        rule.onNodeWithText("投票する").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(current.value to setOf(1), vote) }
    }

    @Test fun quoteCanBeOfferedWithoutAProvidedBoostHandler() {
        var received: Pair<TimelineStatus, QuoteMode>? = null
        rule.setContent { MaterialTheme {
            StatusCard(post().copy(poll = null), onStatusClick = null, onUnavailableAction = {},
                actions = StatusActions(onQuote = { status, mode -> received = status to mode }))
        } }
        rule.onNodeWithContentDescription("ブースト（長押しで引用を選択）").performTouchInput { longClick() }
        rule.onNodeWithText("ブースト").assertIsNotEnabled()
        rule.onNodeWithText("引用（リンク）").performClick()
        rule.runOnIdle { assertEquals(post().copy(poll = null) to QuoteMode.Link, received) }
    }

    @Test fun favouriteListLongPressRemainsAvailableWithoutAMutationHandler() {
        var opened: String? = null
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalFavouriteListOpener provides { opened = it }) {
                StatusCard(post().copy(poll = null, favouritesCount = 1), onStatusClick = null, onUnavailableAction = {})
            }
        } }
        rule.onNodeWithContentDescription("お気に入り（長押しで一覧）").performTouchInput { longClick() }
        rule.runOnIdle { assertEquals("original", opened) }
    }

    private val preferences = StatusDisplayPreferences(
        timelineDisplay = TimelineDisplayPreferences(hiddenActions = emptySet()),
    )

    private fun post() = TimelineStatus(
        timelineId = "original", statusId = "original", createdAt = "2026-11-02T00:00:00Z",
        author = StatusAuthor("author", "Author", "author@example.test", ""), boostedBy = null,
        contentHtml = "Hello", spoilerText = "", sensitive = false, visibility = "public",
        url = "https://example.test/@author/original", repliesCount = 0, boostsCount = 0, favouritesCount = 0,
        mediaAttachments = emptyList(),
        poll = StatusPoll("poll", null, false, false, 0, null, false, emptySet(),
            listOf(PollOption("first", null), PollOption("second", null))),
    )
}
