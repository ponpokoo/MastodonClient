package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.status.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PollCardDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val poll = StatusPoll("poll", null, false, true, 18, 10, false, emptySet(),
        listOf(PollOption("りんご", null), PollOption("みかん", null), PollOption("ぶどう", null)))

    @Test fun hiddenResultsUseCheckboxesAndPendingVotePreventsFurtherInput() {
        var pending by mutableStateOf(false)
        val votes = mutableListOf<Set<Int>>()
        rule.setContent { MastodonClientTheme {
            PollCard(poll, onVote = { votes += it; pending = true }, voting = pending)
        } }
        rule.onNodeWithText("終了まで結果は非表示").assertIsDisplayed()
        rule.onAllNodes(isToggleable()).assertCountEquals(3)
        rule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        rule.onNodeWithTag("poll_vote").assertIsNotEnabled()
        rule.onNodeWithText("りんご").performClick()
        rule.onNodeWithText("みかん").performClick()
        rule.onNodeWithTag("poll_vote").performClick()
        rule.onNodeWithText("投票中…").assertIsNotEnabled()
        assertEquals(listOf(setOf(0, 1)), votes)
        rule.runOnIdle { pending = false }
        rule.onNodeWithTag("poll_vote").assertIsEnabled()
    }

    @Test fun endedResultsUseVotersAndMissingVoterCountShowsOnlyVotes() {
        var value by mutableStateOf(poll.copy(expired = true, voted = true, ownVotes = setOf(0), options =
            listOf(PollOption("りんご", 8), PollOption("みかん", 6), PollOption("ぶどう", 4))))
        rule.setContent { MastodonClientTheme { PollCard(value, onVote = {}) } }
        rule.onNodeWithText("8人（80%）").assertIsDisplayed()
        rule.onNodeWithTag("poll_vote").assertDoesNotExist()
        rule.runOnIdle { value = value.copy(votersCount = null) }
        rule.onNodeWithText("8票").assertIsDisplayed()
        rule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
    }
}
