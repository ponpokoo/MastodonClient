package io.github.ponpokoo.transitionprototype

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.Espresso
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class PrototypeAppDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun additionalPostVariantsRenderAndSensitiveImageCanBeHiddenAgain() {
        val timeline = rule.onNodeWithTag("timeline_list")
        timeline.performScrollToIndex(2)
        rule.onNodeWithText("今日はゆっくり読書をしています。", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("toot_text_only_media_0").assertDoesNotExist()

        timeline.performScrollToIndex(3)
        rule.onNodeWithText("湖と山を楽しむ散歩コース").assertIsDisplayed()
        val preview = rule.onNodeWithTag("preview_card_image", useUnmergedTree = true)
        val screenshot = rule.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "post-layout-url-preview.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("URL thumbnail bounds: ${preview.fetchSemanticsNode().boundsInRoot}", preview.isDisplayed())

        timeline.performScrollToIndex(4)
        rule.onNodeWithTag("toot_sensitive_image_media_0").assertDoesNotExist()
        rule.onNodeWithText("閲覧注意のメディアを表示").performClick()
        rule.onNodeWithTag("toot_sensitive_image_media_0").assertIsDisplayed()
        rule.onNodeWithText("閲覧注意に戻す").performClick()
        rule.onNodeWithTag("toot_sensitive_image_media_0").assertDoesNotExist()
        rule.onNodeWithText("閲覧注意のメディアを表示").assertIsDisplayed()
    }

    @Test
    fun bundledImagesAreDecodedByTheProductionImageLoader() {
        val thumbnail = rule.onNodeWithTag("toot_single_media_0")
        rule.waitUntil(10_000) {
            val pixels = thumbnail.captureToImage().toPixelMap()
            (1..4).sumOf { x -> (1..4).count { y ->
                val color = pixels[pixels.width * x / 5, pixels.height * y / 5]
                maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) > 0.12f
            } } >= 4
        }
    }

    @Test
    fun tabsKeepPostActionsScrollAndSearchInput() {
        favourite().performClick()
        favouriteCount().assertExists()
        rule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
        val position = timelinePosition()
        val beforeBounds = rule.onNodeWithTag("toot_gallery_media_0").fetchSemanticsNode().boundsInRoot
        assertTrue(position > 0f)
        rule.onNodeWithTag("main_tab_explore").performClick().assertIsSelected()
        rule.onNodeWithTag("search_open").performClick()
        rule.onNodeWithTag("search_input").performTextInput("lake")
        rule.onNodeWithTag("search_input").performImeAction()
        rule.onNodeWithTag("main_tab_notifications").performClick().assertIsSelected()
        rule.onNodeWithTag("notifications_screen").assertIsDisplayed()
        rule.onNodeWithTag("main_tab_profile").performClick().assertIsSelected()
        rule.onNodeWithTag("profile_screen").assertIsDisplayed()
        rule.onNodeWithTag("main_tab_explore").performClick()
        rule.onNodeWithText("lake").assertExists()
        rule.onNodeWithTag("main_tab_home").performClick()
        val afterBounds = rule.onNodeWithTag("toot_gallery_media_0").fetchSemanticsNode().boundsInRoot
        assertEquals(position, timelinePosition(), 1f)
        assertEquals(beforeBounds.top, afterBounds.top, 1f)
        rule.onNodeWithTag("main_tab_home").performClick()
        rule.waitForIdle()
        assertEquals(0f, timelinePosition(), 0.01f)
        favouriteCount().assertExists()
    }

    @Test
    fun mainTabsUseNavigationAndAuthorOpensProfile() {
        rule.onNodeWithTag("timeline_list").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        favourite().performClick()
        rule.onAllNodesWithTag("status_author_avatar")[0].performClick()
        rule.onNodeWithTag("account_profile").assertIsDisplayed()
        rule.onNodeWithTag("prototype_media_viewer").assertDoesNotExist()
        back()
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        favouriteCount().assertExists()
    }

    @Test
    fun postActionsAreSharedBetweenTimelineAndAccountProfile() {
        favourite().performClick()
        favouriteCount().assertExists()
        rule.onAllNodesWithTag("status_author_avatar")[0].performClick()
        rule.onNodeWithTag("profile_status_pager").performTouchInput { swipeUp() }
        rule.onNode(hasContentDescription("お気に入り", substring = true) and hasAnyAncestor(hasTestTag("account_profile"))).performClick()
        rule.onNode(hasText("12") and hasAnyAncestor(hasTestTag("account_profile"))).assertExists()
        back()
        rule.onNode(hasText("12") and hasAnyAncestor(hasTestTag("timeline_list"))).assertExists()
    }

    @Test
    fun imageTransitionUsesTheThumbnailInTheScreenThatWasTapped() {
        rule.onAllNodesWithTag("status_author_avatar")[0].performClick()
        rule.onNodeWithTag("profile_status_pager").performTouchInput { swipeUp() }
        val profileImage = rule.onNode(hasTestTag("toot_single_media_0") and hasAnyAncestor(hasTestTag("account_profile")))
        val profileBounds = profileImage.fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        profileImage.performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(profileBounds, rule.onNodeWithTag("prototype_media_motion").fetchSemanticsNode().config[PrototypeImageBounds])
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        back()
        back()
        val homeImage = rule.onNode(hasTestTag("toot_single_media_0") and hasAnyAncestor(hasTestTag("timeline_list")))
        val homeBounds = homeImage.fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        homeImage.performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(homeBounds, rule.onNodeWithTag("prototype_media_motion").fetchSemanticsNode().config[PrototypeImageBounds])
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        back()
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
    private fun favourite() = rule.onAllNodes(hasContentDescription("お気に入り", substring = true) and hasAnyAncestor(hasTestTag("timeline_list")))[0]
    private fun favouriteCount() = rule.onNode(hasText("13") and hasAnyAncestor(hasTestTag("timeline_list")))
    private fun viewerScale() = rule.onNodeWithTag("prototype_media_viewer").fetchSemanticsNode().config[PrototypeViewerScale]
    private fun back() {
        Espresso.pressBackUnconditionally()
        rule.waitForIdle()
    }
}
