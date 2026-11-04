package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.ponpokoo.mastodonclient.domain.model.MastodonList
import io.github.ponpokoo.mastodonclient.feature.status.ListPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListPickerDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun hostWindowResizeShrinksTheListAndKeepsItsLastItemReachable() {
        val heightScale = mutableStateOf(1f)
        val lists = (1..40).map { MastodonList("list-$it", "リスト $it") }
        var selected: String? = null
        rule.setContent {
            val actualWindow = LocalWindowInfo.current
            val window = remember(actualWindow) {
                object : WindowInfo by actualWindow {
                    override val containerDpSize get() = actualWindow.containerDpSize.let {
                        it.copy(height = it.height * heightScale.value)
                    }
                    override val containerSize get() = actualWindow.containerSize.let {
                        IntSize(it.width, (it.height * heightScale.value).toInt())
                    }
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                MaterialTheme { ListPickerDialog(lists, false, {}, { selected = it }) }
            }
        }
        val list = rule.onNode(hasScrollToNodeAction())
        val original = list.fetchSemanticsNode().boundsInWindow
        rule.runOnIdle { heightScale.value = 0.6f }
        val resized = list.fetchSemanticsNode().boundsInWindow
        assertTrue("The list must shrink with its host window: $original -> $resized",
            resized.height < original.height - 1f)
        list.performScrollToNode(hasText(lists.last().title))
        rule.onNodeWithText(lists.last().title).performClick()
        rule.runOnIdle { assertEquals(lists.last().id, selected) }
        rule.onNodeWithText("閉じる").performClick()
    }

    @Test fun loadedListsCanBeScrolledAndSelectedByStringId() {
        val loading = mutableStateOf(true)
        val open = mutableStateOf(true)
        val lists = (1..40).map { MastodonList("list-$it", "リスト $it") }
        var selected: String? = null
        rule.setContent {
            MaterialTheme {
                if (open.value) ListPickerDialog(lists, loading.value, { open.value = false }) {
                    selected = it
                    open.value = false
                }
            }
        }
        rule.onNode(isDialog()).assertExists()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        rule.onNodeWithText(lists.first().title).assertDoesNotExist()
        rule.runOnIdle { loading.value = false }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(lists.last().title))
        rule.onNodeWithText(lists.last().title).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(lists.last().id, selected) }
    }

    @Test fun emptyListDialogCanBeClosedWithoutSelecting() {
        val open = mutableStateOf(true)
        var selected: String? = null
        rule.setContent {
            MaterialTheme {
                if (open.value) ListPickerDialog(emptyList(), false, { open.value = false }) {
                    selected = it
                }
            }
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("閉じる").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertNull(selected) }
    }
}
