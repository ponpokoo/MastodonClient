package io.github.ponpokoo.transitionprototype

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test

class PrototypeMenuDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackClosesViewerBeforeReturningToMenu() {
        rule.onNodeWithTag("single_image_sample").performClick()
        rule.onNodeWithTag("thumbnail").performClick()
        rule.onNodeWithTag("viewer").assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag("viewer").assertDoesNotExist()
        rule.onNodeWithTag("thumbnail").assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag("prototype_menu").assertIsDisplayed()

        rule.onNodeWithTag("reference_sample").performClick()
        rule.onNodeWithTag("reference_thumbnail_0").performClick()
        rule.onNodeWithTag("reference_gallery")
            .assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, true))
        pressBack()
        rule.onNodeWithTag("reference_gallery")
            .assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, false))
        pressBack()
        rule.onNodeWithTag("prototype_menu").assertIsDisplayed()
    }

    @Test
    fun exitButtonsAllowSelectingEitherSampleAgain() {
        repeat(2) {
            rule.onNodeWithTag("single_image_sample").performClick()
            rule.onNodeWithTag("thumbnail").assertIsDisplayed()
            rule.onNodeWithTag("viewer").assertDoesNotExist()
            rule.onNodeWithTag("prototype_exit").performClick()
            rule.onNodeWithTag("prototype_menu").assertIsDisplayed()

            rule.onNodeWithTag("reference_sample").performClick()
            rule.onNodeWithTag("reference_gallery")
                .assert(SemanticsMatcher.expectValue(ReferenceViewerOpen, false))
            rule.onNodeWithTag("prototype_exit").performClick()
            rule.onNodeWithTag("prototype_menu").assertIsDisplayed()
        }
    }
}
