package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.StatusContentText
import io.github.ponpokoo.mastodonclient.feature.status.CollapsibleStatusBody
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatusCollapseDeviceTest {
    @get:Rule val rule = createComposeRule()

    private fun lines(count: Int) = "<p>" + (1..count).joinToString("<br>") { "行$it" } + "</p>"

    @Composable
    private fun Body(html: String, account: String = "account", row: String = "row", emojis: Map<String, String> = emptyMap(),
        onLink: (String) -> Unit = {}, onOther: () -> Unit = {}) {
        CollapsibleStatusBody(account, "scope", row, "status", html) { maxLines, onLayout ->
            StatusContentText(html, Modifier.testTag("body"), customEmojis = emojis,
                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp), emojiHeight = 24.dp,
                maxLines = maxLines, onTextLayout = onLayout, onLinkClick = onLink, onNonLinkClick = onOther)
        }
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun exactLineBoundaryExpandsWithoutLosingTextAndCollapses() {
        val count = mutableStateOf(11)
        rule.setContent { MaterialTheme { Column(Modifier.width(280.dp)) { Body(lines(count.value)) } } }
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.runOnIdle { count.value = 12 }
        assertEquals(12, layout().lineCount)
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.runOnIdle { count.value = 13 }
        assertEquals(12, layout().lineCount)
        assertTrue(layout().isLineEllipsized(11))
        val visible = rule.onNodeWithTag("body").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertFalse(visible.contains("行13"))
        rule.onNodeWithText("続きを読む").performClick()
        assertEquals(13, layout().lineCount)
        assertTrue(layout().layoutInput.text.text.endsWith("行13"))
        rule.onNodeWithText("閉じる").performClick()
        assertEquals(12, layout().lineCount)
    }

    @Test fun widthAndFontScaleRecalculateOverflowWhileKeepingExpandedChoice() {
        val width = mutableStateOf(280)
        val scale = mutableStateOf(1f)
        // Fits at the original width, wraps to more than 12 lines after either change.
        val html = "<p>${(1..10).joinToString("<br>") { "日本語の本文と折り返し確認です" }}</p>"
        rule.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                    Column(Modifier.width(width.value.dp)) { Body(html) }
                }
            }
        }
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.runOnIdle { width.value = 130 }
        rule.onNodeWithText("続きを読む").performClick()
        assertTrue(layout().lineCount > 12)
        rule.runOnIdle { width.value = 280 }
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.runOnIdle { scale.value = 2f }
        val changed = layout()
        assertEquals("Expanded choice must survive width/font changes", Int.MAX_VALUE, changed.layoutInput.maxLines)
        assertTrue("lines=${changed.lineCount}, fontScale=${changed.layoutInput.density.fontScale}, font=${changed.layoutInput.style.fontSize}, width=${changed.layoutInput.constraints.maxWidth}", changed.lineCount > 12)
        rule.onNodeWithText("閉じる").assertExists()
    }

    @Test fun visibleLinksStillOpenButEllipsisCannotOpenHiddenLink() {
        val events = mutableListOf<String>()
        val html = "<p><a href=\"https://example.test/visible\">visible</a><br>" +
            (1..10).joinToString("<br>") { "行$it" } +
            "<br><a href=\"https://example.test/long\">${"link".repeat(30)}</a><br>HIDDEN</p>"
        rule.setContent { MaterialTheme { Column(Modifier.width(220.dp)) {
            Body(html, onLink = events::add, onOther = { events.add("detail") })
        } } }
        var result = layout()
        rule.onNodeWithTag("body").performTouchInput { click(result.getBoundingBox(1).center) }
        result = layout()
        rule.onNodeWithTag("body").performTouchInput {
            click(Offset(result.size.width - 1f, (result.getLineTop(11) + result.getLineBottom(11)) / 2))
        }
        rule.runOnIdle { assertEquals(listOf("https://example.test/visible", "detail"), events) }
        rule.onNodeWithText("続きを読む").performClick()
        result = layout()
        val offset = result.layoutInput.text.text.indexOf("link") + 1
        rule.onNodeWithTag("body").performTouchInput { click(result.getBoundingBox(offset).center) }
        rule.runOnIdle { assertEquals("https://example.test/long", events.last()) }
    }

    @Test fun hiddenEmojiPlaceholderReappearsOnExpansion() {
        val html = "<p>" + (1..12).joinToString("<br>") { "行$it :e:" } + "<br>末尾 :e:</p>"
        val emojis = mapOf("e" to "android.resource://io.github.ponpokoo.mastodonclient/${R.drawable.nagisa_launcher_art}")
        rule.setContent { MaterialTheme { Column(Modifier.width(280.dp)) { Body(html, emojis = emojis) } } }
        assertEquals(13, layout().placeholderRects.size)
        assertNull(layout().placeholderRects.last())
        rule.onNodeWithText("続きを読む").performClick()
        assertTrue(layout().placeholderRects.all { it != null })
    }

    @Test fun savedExpansionRestoresOnlyForSameAccountRowAndBody() {
        val account = mutableStateOf("a|1")
        val row = mutableStateOf("row")
        val html = mutableStateOf(lines(13))
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        val shown = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (shown.value) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                        Column(Modifier.width(280.dp)) { Body(html.value, account.value, row.value) }
                    }
                }
            }
        }
        rule.onNodeWithText("続きを読む").performClick()
        var saved = emptyMap<String, List<Any?>>()
        fun recreate(change: () -> Unit = {}) {
            rule.runOnIdle { saved = registry.value.performSave(); shown.value = false }
            rule.runOnIdle { change(); registry.value = SaveableStateRegistry(saved) { true }; shown.value = true }
        }
        recreate()
        rule.onNodeWithText("閉じる").assertExists()
        recreate { account.value = "b|1" }
        rule.onNodeWithText("続きを読む").assertExists()
        rule.onNodeWithText("続きを読む").performClick()
        recreate { account.value = "b|2" }
        rule.onNodeWithText("続きを読む").assertExists()
        rule.onNodeWithText("続きを読む").performClick()
        recreate { row.value = "boost-row" }
        rule.onNodeWithText("続きを読む").assertExists()
        rule.onNodeWithText("続きを読む").performClick()
        recreate { html.value = lines(14) }
        rule.onNodeWithText("続きを読む").assertExists()
    }

    @Test fun lazyReentryKeepsExpansionAndCollapseReturnsToSamePost() {
        val list = LazyListState()
        val post = mutableStateOf(post(lines(55)))
        rule.setContent { MaterialTheme {
            LazyColumn(Modifier.width(300.dp).height(450.dp), state = list) {
                item(key = "target") { StatusCard(post.value, onStatusClick = {}, onUnavailableAction = {}) }
                items(30, key = { "filler-$it" }) { Text("短文$it", Modifier.height(80.dp)) }
            }
        } }
        rule.onNodeWithText("続きを読む").performScrollTo().performClick()
        rule.runOnIdle { post.value = post.value.copy(favourited = true); list.requestScrollToItem(20) }
        rule.waitForIdle()
        rule.runOnIdle { list.requestScrollToItem(0) }
        rule.waitForIdle()
        rule.onNodeWithText("閉じる").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertTrue("Collapse left the list deep inside the old full-height post", list.firstVisibleItemScrollOffset < 300)
        }
        rule.onNodeWithText("続きを読む").assertExists()
    }

    @Test fun cwAndDetailBypassAutomaticCollapse() {
        val warning = mutableStateOf("CWの説明")
        val collapse = mutableStateOf(true)
        rule.setContent { MaterialTheme {
            Column(Modifier.width(300.dp)) {
                StatusCard(post(lines(13)).copy(spoilerText = warning.value), onStatusClick = null,
                    onUnavailableAction = {}, collapseLongContent = collapse.value)
            }
        } }
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.onNodeWithText("内容を表示").performClick()
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.onNodeWithText("内容を隠す").performClick()
        rule.runOnIdle { warning.value = ""; collapse.value = false }
        rule.onNodeWithTag("status_body_toggle").assertDoesNotExist()
        rule.onNodeWithText("行13", substring = true, useUnmergedTree = true).assertExists()
    }

    private fun post(html: String) = TimelineStatus(
        timelineId = "row", statusId = "status", createdAt = "2026-10-21T00:00:00Z",
        author = StatusAuthor("author", "なぎさ", "nagisa@example.test", ""), boostedBy = null,
        contentHtml = html, spoilerText = "", sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
    )
}
