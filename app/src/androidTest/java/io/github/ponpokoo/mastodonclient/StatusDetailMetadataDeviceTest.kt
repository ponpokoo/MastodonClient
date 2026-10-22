package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusDetailMetadataDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val opened = mutableListOf<String>()
    private val status = TimelineStatus(
        timelineId = "post", statusId = "post", createdAt = "2026-10-22T00:00:00Z",
        author = StatusAuthor("author", "Alice", "alice", ""), boostedBy = null,
        contentHtml = "", spoilerText = "", sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
        applicationName = "Tusky",
    )

    private fun tapMetadata(application: Boolean) {
        val node = rule.onNodeWithText("Tusky", substring = true, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val offset = if (application) layout.layoutInput.text.text.indexOf("Tusky") + 2 else 0
        node.performTouchInput { click(layout.getBoundingBox(offset).center) }
    }

    @Test fun tappingApplicationOpensItsWebsiteAndTappingDateDoesNothing() {
        val website = mutableStateOf("  HTTPS://client.example/about?from=post#download  ")
        val expectedUrl = "https://client.example/about?from=post#download"
        rule.setContent {
            MaterialTheme {
                StatusDetailMetadata(status.copy(applicationWebsite = website.value), opened::add, {}, {})
            }
        }
        tapMetadata(application = false)
        rule.runOnIdle { assertTrue(opened.isEmpty()) }
        tapMetadata(application = true)
        rule.runOnIdle {
            assertEquals(listOf(expectedUrl), opened)
            website.value = "http://other-client.example/"
        }
        tapMetadata(application = true)
        rule.runOnIdle {
            assertEquals(listOf(expectedUrl, website.value), opened)
        }
    }

    @Test fun missingOrInvalidWebsiteKeepsApplicationAsPlainText() {
        val website = mutableStateOf<String?>(null)
        rule.setContent {
            MaterialTheme {
                StatusDetailMetadata(status.copy(applicationWebsite = website.value), opened::add, {}, {})
            }
        }
        listOf(null, "", " ", "not a URL", "https://", "javascript:alert(1)", "intent://client").forEach { value ->
            rule.runOnIdle { website.value = value }
            tapMetadata(application = true)
            rule.runOnIdle { assertTrue("Unexpected navigation for $value", opened.isEmpty()) }
        }
    }

    @Test fun linkedApplicationLooksIdenticalToPlainTextInLightAndDarkThemes() {
        val website = mutableStateOf<String?>(null)
        val dark = mutableStateOf(false)
        rule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                Surface {
                    Column {
                        StatusDetailMetadata(status.copy(applicationWebsite = website.value), opened::add, {}, {})
                    }
                }
            }
        }
        listOf(false, true).forEach { darkTheme ->
            rule.runOnIdle {
                dark.value = darkTheme
                website.value = null
                opened.clear()
            }
            val node = rule.onNodeWithText("Tusky", substring = true, useUnmergedTree = true)
            val plain = node.captureToImage().asAndroidBitmap()
            rule.runOnIdle { website.value = "https://client.example/" }
            val linked = node.captureToImage().asAndroidBitmap()
            assertTrue("Link changed text appearance (dark=$darkTheme)", plain.sameAs(linked))
            tapMetadata(application = true)
            rule.runOnIdle { assertEquals(listOf(website.value), opened) }
        }
    }
}
