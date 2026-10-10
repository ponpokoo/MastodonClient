package io.github.ponpokoo.transitionprototype

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.Espresso

class PrototypeAppDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabsKeepPostActionsScrollAndSearchInput() {
        rule.onNodeWithTag("toot_single_favourite").performClick().assertIsSelected()
        rule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
        val position = timelinePosition()
        assertTrue(position > 0f)
        rule.onNodeWithTag("main_tab_explore").performClick().assertIsSelected()
        rule.onNodeWithTag("search_input").performTextInput("lake")
        rule.onNodeWithTag("main_tab_notifications").performClick().assertIsSelected()
        rule.onNodeWithTag("notifications_screen").assertIsDisplayed()
        rule.onNodeWithTag("main_tab_profile").performClick().assertIsSelected()
        rule.onNodeWithTag("profile_screen").assertIsDisplayed()
        rule.onNodeWithTag("main_tab_explore").performClick()
        rule.onNodeWithText("lake").assertExists()
        rule.onNodeWithTag("main_tab_home").performClick()
        assertEquals(position, timelinePosition(), 1f)
        rule.onNodeWithTag("main_tab_home").performClick()
        rule.waitForIdle()
        assertEquals(0f, timelinePosition(), 0.01f)
        rule.onNodeWithTag("toot_single_favourite").assertIsSelected()
        rule.onNodeWithTag("toot_single_boost").assertIsNotSelected()
    }

    @Test
    fun mainTabsUseNavigationAndAuthorOpensProfile() {
        rule.onNodeWithTag("timeline_list").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        rule.onNodeWithTag("toot_single_favourite").performClick()
        rule.onNodeWithTag("toot_single_author").performClick()
        rule.onNodeWithTag("account_profile").assertIsDisplayed()
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        back()
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        rule.onNodeWithTag("toot_single_favourite").assertIsSelected()
    }

    @Test
    fun galleryOpensSelectedImageAndBackRestoresPostPosition() {
        rule.onNodeWithTag("timeline_list").performScrollToIndex(1)
        rule.onNodeWithTag("toot_gallery_media_1").performClick()
        rule.onNodeWithTag("prototype_media_viewer").assertIsDisplayed()
        assertEquals(1, viewerPage())
        rule.onNodeWithTag("prototype_media_viewer").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(2, viewerPage())
        back()
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        rule.onNodeWithTag("toot_gallery_media_1").assertIsDisplayed()
        assertTrue(timelinePosition() >= 1f)
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        rule.onNodeWithTag("main_tab_home").performClick()
        rule.onNodeWithTag("toot_single_media_0").performClick()
        assertEquals(0, viewerPage())
        rule.onNodeWithTag("prototype_media_close").performClick()
        rule.onNodeWithTag("toot_single_media_0").assertIsDisplayed()
    }

    @Test
    fun imageTapTogglesControlsAndZoomPreventsDownwardDismissal() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val viewer = rule.onNodeWithTag("prototype_media_viewer")
        viewer.performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("prototype_media_close").fetchSemanticsNodes().isEmpty() }
        viewer.assertIsDisplayed()
        viewer.performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("prototype_media_close").fetchSemanticsNodes().isNotEmpty() }
        viewer.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(2.5f, viewerScale(), 0.01f)
        viewer.performTouchInput { swipe(center, center + Offset(0f, height * 0.25f), 350) }
        viewer.assertIsDisplayed()
        assertEquals(2.5f, viewerScale(), 0.01f)
        viewer.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(1f, viewerScale(), 0.01f)
        viewer.performTouchInput { swipe(center, center + Offset(0f, height * 0.05f), 350) }
        viewer.assertIsDisplayed()
        rule.waitForIdle()
        viewer.performTouchInput { swipe(center, center + Offset(0f, height * 0.25f), 350) }
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        rule.onNodeWithTag("toot_single_media_0").assertIsDisplayed()
    }

    @Test
    fun pinchLimitsZoomAndReturnsUndersizedImageToFit() {
        rule.onNodeWithTag("toot_single_media_0").performClick()
        val viewer = rule.onNodeWithTag("prototype_media_viewer")
        repeat(2) {
            viewer.performTouchInput {
                down(0, center - Offset(width * 0.12f, 0f))
                down(1, center + Offset(width * 0.12f, 0f))
                updatePointerTo(0, center - Offset(width * 0.45f, 0f))
                updatePointerTo(1, center + Offset(width * 0.45f, 0f))
                move(300)
                up(0)
                up(1)
            }
        }
        assertEquals(5f, viewerScale(), 0.01f)
        viewer.performTouchInput { doubleClick(center) }
        assertEquals(1f, viewerScale(), 0.01f)
        viewer.performTouchInput {
            down(0, center - Offset(width * 0.3f, 0f))
            down(1, center + Offset(width * 0.3f, 0f))
            updatePointerTo(0, center - Offset(width * 0.08f, 0f))
            updatePointerTo(1, center + Offset(width * 0.08f, 0f))
            move(300)
        }
        assertEquals(0.5f, viewerScale(), 0.01f)
        viewer.performTouchInput { up(0); up(1) }
        rule.waitForIdle()
        assertEquals(1f, viewerScale(), 0.01f)
        viewer.assertIsDisplayed()
        back()
        rule.onNodeWithTag("toot_single_media_0").assertIsDisplayed()
    }

    private fun timelinePosition() = rule.onNodeWithTag("timeline_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun viewerPage() = rule.onNodeWithTag("prototype_media_viewer").fetchSemanticsNode().config[PrototypeViewerPage]
    private fun viewerScale() = rule.onNodeWithTag("prototype_media_viewer").fetchSemanticsNode().config[PrototypeViewerScale]
    private fun back() {
        Espresso.pressBackUnconditionally()
        rule.waitForIdle()
    }
}
