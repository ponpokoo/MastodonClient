package io.github.ponpokoo.transitionprototype

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileUrlDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun profileUrlUsesTheProvidedUrlAndHidesWhenMissing() {
        val profile = mutableStateOf(UserProfile(
            author = PrototypeOwnAuthor,
            headerUrl = "",
            noteHtml = "<p>自己紹介</p>",
            followersCount = 0,
            followingCount = 0,
            statusesCount = 0,
            statuses = emptyList(),
        ))
        var openedUrl: String? = null
        rule.activity.runOnUiThread {
            rule.activity.setContent {
                MastodonClientTheme(ThemeMode.Dark) {
                    Surface {
                        ProfileContent(
                            state = ProfileUiState(profile = profile.value),
                            padding = PaddingValues(),
                            onRetry = {}, onRefresh = {}, onStatusClick = {},
                            onOpenLink = { openedUrl = it },
                            onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                            onFavourite = {}, onReact = { _, _ -> },
                            onAccountClick = {}, onMediaClick = { _, _ -> },
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("profile_url").assertDoesNotExist()
        // The canonical URL can differ from an account handle and the browsing server.
        val suppliedUrl = "https://remote.example/users/profile-identifier"
        rule.runOnIdle { profile.value = profile.value.copy(url = suppliedUrl) }
        rule.onNodeWithTag("profile_url").assertIsDisplayed().performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(suppliedUrl, openedUrl) }
        rule.runOnIdle { profile.value = profile.value.copy(url = " ") }
        rule.onNodeWithTag("profile_url").assertDoesNotExist()
    }
}
