package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.common.htmlToAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatusContentTextDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unicodeLineSeparatorIsNormalizedWithoutChangingOtherBreaks() {
        val html = "<p>first\u2028second<br>third</p><p>fourth\nfifth\u2029sixth</p>"
        assertEquals(
            "first\nsecond\nthird\n\nfourth fifth\u2029sixth",
            htmlToAnnotatedString(html, Color.Blue).text,
        )
    }

    @Test fun unicodeLineSeparatorRendersTwoLinesAndPreservesLinksOnBothSides() {
        val firstUrl = "https://example.test/first"
        val secondUrl = "https://example.test/second"
        val openedLinks = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                StatusContentText(
                    contentHtml = "<p><a href=\"$firstUrl\">first</a>\u2028<a href=\"$secondUrl\">second</a></p>",
                    modifier = Modifier.testTag("profile_note"),
                    onLinkClick = openedLinks::add,
                )
            }
        }
        val node = rule.onNodeWithTag("profile_note")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2, layout.lineCount)
        assertEquals(6, layout.getLineStart(1))
        node.performTouchInput { click(layout.getBoundingBox(1).center) }
        node.performTouchInput { click(layout.getBoundingBox(7).center) }
        rule.runOnIdle { assertEquals(listOf(firstUrl, secondUrl), openedLinks) }
    }
}
