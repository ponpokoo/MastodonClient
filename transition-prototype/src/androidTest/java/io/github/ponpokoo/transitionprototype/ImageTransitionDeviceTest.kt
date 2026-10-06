package io.github.ponpokoo.transitionprototype

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageTransitionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun openingAndClosingActuallyMatchSharedImage() {
        rule.mainClock.autoAdvance = false
        capture("01-list")
        rule.onNodeWithTag("thumbnail").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        capture("02-opening")
        rule.mainClock.advanceTimeBy(80)
        capture("02-opening-middle")
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("viewer").assertExists()
        capture("03-fullscreen")

        rule.onNodeWithTag("close").performClick()
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("prototype").assert(SemanticsMatcher("rounds the image before shrinking") {
            it.config[RoundingBeforeClose] &&
                it.config[ImageCornerRadius] > 0f &&
                !it.config[ImageTransitionActive]
        })
        capture("04-rounding-before-close")
        rule.mainClock.advanceTimeBy(112)
        assertSharedTransition()
        rule.onNodeWithTag("prototype").assert(SemanticsMatcher.expectValue(RoundingBeforeClose, false))
        capture("04-closing")
        rule.mainClock.advanceTimeBy(80)
        capture("04-closing-middle")
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        rule.onNodeWithTag("thumbnail").assertExists()
    }

    @Test
    fun backDuringOpeningRestoresThumbnailAndCanStartAnotherTransition() {
        rule.mainClock.autoAdvance = false
        val before = rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("thumbnail").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        assertEquals(before, rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("thumbnail").performClick()
        rule.mainClock.advanceTimeBy(80)
        assertSharedTransition()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("viewer").assertExists()
    }

    @Test
    fun closingBouncesAndSettlesAtOriginalThumbnail() {
        rule.mainClock.autoAdvance = false
        val before = rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("thumbnail").performClick()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag("close").performClick()
        var sawBounce = false
        repeat(48) {
            rule.mainClock.advanceTimeBy(16)
            val fraction = rule.onNodeWithTag("prototype").fetchSemanticsNode().config[ImageFitFraction]
            if (fraction < 0f && !sawBounce) {
                sawBounce = true
                capture("05-closing-bounce")
            }
        }
        assertTrue("Closing should briefly shrink past the thumbnail size", sawBounce)
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        rule.onNodeWithTag("prototype").assert(SemanticsMatcher.expectValue(ImageTransitionActive, false))
        assertEquals(before, rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun systemBackPreservesScrolledThumbnailPositionAndAllowsReopening() {
        rule.onNodeWithTag("timeline").performScrollToIndex(3)
        val before = rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("thumbnail").performClick()
        rule.onNodeWithTag("viewer").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        assertEquals(before, rule.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("thumbnail").performClick()
        rule.onNodeWithTag("viewer").assertExists()
    }

    private fun assertSharedTransition() {
        rule.onNodeWithTag("prototype")
            .assert(SemanticsMatcher.expectValue(ImageMatched, true))
            .assert(SemanticsMatcher.expectValue(ImageTransitionActive, true))
    }

    private fun capture(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "transition-captures")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { output ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
