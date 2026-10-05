package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileWebMenuDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val openedLinks = mutableListOf<String>()

    @Test fun ownProfileMenuOpensProfileSettingsAndServerAboutAndClosesAfterSelection() {
        showProfile(own = true, profileUrl = "https://fedibird.com/@alice")
        openMenu()
        val settingsBounds = rule.onNodeWithText("マストドンの設定").fetchSemanticsNode().boundsInRoot
        val aboutBounds = rule.onNodeWithText("このサーバーについて").fetchSemanticsNode().boundsInRoot
        assertTrue(aboutBounds.top >= settingsBounds.bottom)
        select("ブラウザで開く")
        openMenu()
        select("マストドンの設定")
        openMenu()
        select("このサーバーについて")
        rule.runOnIdle {
            assertEquals(listOf("https://fedibird.com/@alice", "https://fedibird.com/settings/profile",
                "https://fedibird.com/about"), openedLinks)
        }
    }

    @Test fun remoteProfileOpensItsCanonicalUrlAndItsOwnServerAbout() {
        showProfile(own = false, profileUrl = "https://remote.example/@alice")
        openMenu()
        rule.onNodeWithText("マストドンの設定").assertDoesNotExist()
        select("ブラウザで開く")
        openMenu()
        select("このサーバーについて")
        rule.runOnIdle {
            assertEquals(listOf("https://remote.example/@alice", "https://remote.example/about"), openedLinks)
        }
    }

    private fun showProfile(own: Boolean, profileUrl: String) {
        val profile = UserProfile(
            author = StatusAuthor("alice", "Alice", "alice", ""),
            headerUrl = "", noteHtml = "", followersCount = 0, followingCount = 0,
            statusesCount = 0, statuses = emptyList(), url = profileUrl, isOwnProfile = own,
            endReached = true,
        )
        rule.setContent {
            MaterialTheme {
                ProfileContent(
                    state = ProfileUiState(profile = profile),
                    instanceUrl = "https://fedibird.com/",
                    padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                    onOpenLink = { openedLinks += it }, onReply = {}, onBoost = {},
                    onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                    onAccountClick = {}, onMediaClick = { _, _ -> },
                )
            }
        }
    }

    private fun openMenu() {
        rule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
    }

    private fun select(label: String) {
        rule.onNodeWithText(label).performClick()
        rule.onNodeWithText(label).assertDoesNotExist()
    }
}
