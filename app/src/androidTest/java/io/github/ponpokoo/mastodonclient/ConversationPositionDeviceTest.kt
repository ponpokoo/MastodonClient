package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.feature.detail.rememberConversationListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationPositionDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun centersSelectedRowAfterLoadingAndDoesNotRecenterAfterScrolling() {
        val ready = mutableStateOf(false)
        lateinit var list: LazyListState
        rule.setContent {
            list = rememberConversationListState("target", 10, ready.value)
            LazyColumn(Modifier.height(400.dp).testTag("conversation"), state = list) {
                item { Box(Modifier.fillMaxWidth().height(40.dp)) }
                items(10, key = { "ancestor-$it" }) { Box(Modifier.fillMaxWidth().height(80.dp)) }
                item(key = "selected-target") { Box(Modifier.fillMaxWidth().height(80.dp).testTag("target")) }
                items(10, key = { "reply-$it" }) { Box(Modifier.fillMaxWidth().height(80.dp)) }
            }
        }
        rule.runOnIdle { ready.value = true }
        rule.waitForIdle()
        val viewport = rule.onNodeWithTag("conversation").fetchSemanticsNode().boundsInRoot
        val selected = rule.onNodeWithTag("target").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.center.y, selected.center.y, 2f)

        rule.onNodeWithTag("conversation").performTouchInput { swipeUp() }
        rule.waitForIdle()
        var position = 0 to 0
        rule.runOnIdle {
            position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            ready.value = false
        }
        rule.runOnIdle { ready.value = true }
        rule.runOnIdle { assertEquals(position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
    }

    @Test fun tallSelectedRowStartsAtTop() {
        lateinit var list: LazyListState
        rule.setContent {
            list = rememberConversationListState("target", 2, true)
            LazyColumn(Modifier.height(400.dp), state = list) {
                item { Box(Modifier.height(40.dp)) }
                items(2) { Box(Modifier.height(80.dp)) }
                item(key = "selected-target") { Box(Modifier.fillMaxWidth().height(700.dp)) }
                item { Box(Modifier.height(80.dp)) }
            }
        }
        rule.runOnIdle {
            assertEquals(3, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.key == "selected-target" })
        }
    }
}
