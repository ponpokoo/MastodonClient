package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.feature.settings.TimelineDisplayPreview
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalFavouriteListOpener
import io.github.ponpokoo.mastodonclient.feature.timeline.LocalReactionListOpener
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsPreviewDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun previewDoesNotOpenAccountListsFromInheritedActions() {
        var openedLists = 0
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalFavouriteListOpener provides { openedLists++ },
                    LocalReactionListOpener provides { _, _ -> openedLists++ },
                ) {
                    TimelineDisplayPreview(AppPreferences())
                }
            }
        }
        rule.onNodeWithContentDescription("お気に入り").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("お気に入り").performClick()
        rule.onNodeWithTag("displayed_reaction").performTouchInput { longClick() }
        rule.onNodeWithTag("displayed_reaction").performClick()
        rule.runOnIdle { assertEquals(0, openedLists) }
    }

    @Test fun previewAvatarRendersAppIcon() {
        rule.setContent {
            MaterialTheme { TimelineDisplayPreview(AppPreferences()) }
        }
        val avatar = rule.onNodeWithTag("status_author_avatar", useUnmergedTree = true)
        // A failed image request leaves a uniform surface. The app artwork has multiple colours.
        rule.waitUntil(10_000) {
            val pixels = avatar.captureToImage().toPixelMap()
            val colours = mutableSetOf<androidx.compose.ui.graphics.Color>()
            for (y in pixels.height / 4 until pixels.height * 3 / 4) {
                for (x in pixels.width / 4 until pixels.width * 3 / 4) {
                    colours.add(pixels[x, y])
                }
            }
            colours.size > 10
        }
    }
}
