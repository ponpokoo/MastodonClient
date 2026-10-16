package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineGapRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TimelineGapRowDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bothEdgesLoadAndLoadingKeepsHeightWithoutAcceptingAnotherTap() {
        val loading = mutableStateOf(false)
        var taps = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("gap_container")) {
                    TimelineGapRow(loading.value, unavailable = false, error = null, enabled = true, onClick = { taps++ })
                }
            }
        }
        val gap = rule.onNodeWithText("間の投稿を読み込む")
        val initial = gap.fetchSemanticsNode().boundsInRoot
        assertEquals(rule.onNodeWithTag("gap_container").fetchSemanticsNode().boundsInRoot.width, initial.width, 0.5f)
        gap.performTouchInput { click(Offset(1f, center.y)) }
        gap.performTouchInput { click(Offset(width - 1f, center.y)) }
        rule.runOnIdle { assertEquals(2, taps); loading.value = true }

        val busy = rule.onNodeWithText("読み込み中…").assertIsNotEnabled()
        assertEquals(initial.height, busy.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        busy.performTouchInput { click(Offset(1f, center.y)) }
        rule.runOnIdle { assertEquals(2, taps) }
    }

    @Test fun retryUsesWholeRowAndDisabledOrUnavailableRowsDoNotLoad() {
        val enabled = mutableStateOf(true)
        val unavailable = mutableStateOf(false)
        var taps = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    TimelineGapRow(loading = false, unavailable.value, error = "通信に失敗しました", enabled.value, onClick = { taps++ })
                }
            }
        }
        rule.onNodeWithText("再試行").performTouchInput { click(Offset(width - 1f, center.y)) }
        rule.runOnIdle { assertEquals(1, taps); enabled.value = false }
        rule.onNodeWithText("再試行").assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, taps); enabled.value = true; unavailable.value = true }
        rule.onNodeWithText("この区間の投稿を取得できません").assertHasNoClickAction().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, taps) }
    }
}
