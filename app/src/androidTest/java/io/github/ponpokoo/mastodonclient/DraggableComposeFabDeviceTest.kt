package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.feature.timeline.components.DraggableComposeFab
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DraggableComposeFabDeviceTest {
    @get:Rule val rule = createComposeRule()
    private class Store : FabPositionStore {
        override val fabPosition = MutableStateFlow<FabPosition?>(null)
        val writes = mutableListOf<FabPosition>()
        var fail = false
        var resets = 0
        override suspend fun setFabPosition(position: FabPosition) {
            if (fail) throw java.io.IOException("Test failure")
            writes += position
            fabPosition.value = position
        }
        override suspend fun resetFabPosition() {
            if (fail) throw java.io.IOException("Test failure")
            resets++
            fabPosition.value = null
        }
    }
    private val store = Store()
    private var clicks = 0
    private fun show() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp, 400.dp).testTag("area")) {
                    DraggableComposeFab(store, { clicks++ })
                }
            }
        }
        rule.waitForIdle()
    }
    private fun fab() = rule.onNodeWithContentDescription("新規投稿")
    @Test fun draggingBeyondEdgesKeepsEntireButtonInsideArea() {
        show()
        val area = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        val initial = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(-5000f, -5000f)); up()
        }
        rule.waitForIdle()
        val topLeft = fab().fetchSemanticsNode().boundsInRoot
        assertTrue(topLeft.left > area.left && topLeft.top > area.top)
        assertTrue(topLeft.left < initial.left && topLeft.top < initial.top)
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(5000f, 0f)); up()
        }
        rule.waitForIdle()
        val topRight = fab().fetchSemanticsNode().boundsInRoot
        assertTrue(topRight.right < area.right && topRight.top > area.top)
        assertTrue(topRight.left > topLeft.left)
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, 5000f)); up()
        }
        rule.waitForIdle()
        val bottomRight = fab().fetchSemanticsNode().boundsInRoot
        assertTrue(bottomRight.right < area.right && bottomRight.bottom < area.bottom)
        assertEquals(initial, bottomRight)
        rule.runOnIdle { assertEquals(0, clicks) }
    }
    @Test fun resettingAlreadyDefaultPositionDoesNotMaskLaterStoreChanges() {
        show()
        val initial = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(-10f, -10f)); up()
        }
        rule.waitForIdle()
        assertEquals(initial, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(1, store.resets); store.fabPosition.value = FabPosition(.4f, .4f) }
        rule.waitForIdle()
        assertNotEquals(initial, fab().fetchSemanticsNode().boundsInRoot)
    }
    @Test fun releasingNearDefaultResetsButLeavingTargetDoesNot() {
        show()
        val initial = fab().fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { store.fabPosition.value = FabPosition(.4f, .4f) }
        rule.waitForIdle()
        val moved = fab().fetchSemanticsNode().boundsInRoot
        val toTarget = initial.center - moved.center - Offset(10f, 10f)
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(toTarget)
        }
        rule.runOnIdle { assertEquals(0, store.resets); assertTrue(store.writes.isEmpty()) }
        val inTarget = fab().fetchSemanticsNode().boundsInRoot
        assertNotEquals(initial, inTarget) // No snapping while the finger is down.
        fab().performTouchInput { moveBy(-toTarget / 2f); up() }
        rule.runOnIdle { assertEquals(0, store.resets); assertEquals(1, store.writes.size) }
        val outside = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700)
            moveBy(initial.center - outside.center - Offset(10f, 10f)); up()
        }
        rule.waitForIdle()
        assertEquals(initial, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(1, store.resets); assertNull(store.fabPosition.value); assertEquals(0, clicks) }
    }

    @Test fun cancellingOrFailingResetKeepsSavedPosition() {
        show()
        val initial = fab().fetchSemanticsNode().boundsInRoot
        val saved = FabPosition(.4f, .4f)
        rule.runOnIdle { store.fabPosition.value = saved }
        rule.waitForIdle()
        val moved = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(initial.center - moved.center); cancel()
        }
        rule.waitForIdle()
        assertEquals(moved, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(0, store.resets); store.fail = true }
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(initial.center - moved.center); up()
        }
        rule.waitForIdle()
        assertEquals(moved, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(saved, store.fabPosition.value); assertEquals(0, clicks) }
    }
    @Test fun tapAndStationaryLongPressAreDistinct() {
        show()
        fab().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, clicks) }
        fab().performTouchInput { down(center); advanceEventTime(700); up() }
        rule.runOnIdle { assertEquals(1, clicks); assertTrue(store.writes.isEmpty()) }
    }
    @Test fun dragSavesOnceAndCancellationRestoresStart() {
        show()
        val initial = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700)
            moveBy(Offset(-80f, -100f)); up()
        }
        rule.runOnIdle { assertEquals(0, clicks); assertEquals(1, store.writes.size) }
        val moved = fab().fetchSemanticsNode().boundsInRoot
        assertTrue(moved.left < initial.left)
        assertTrue(moved.top < initial.top)
        fab().performTouchInput {
            down(center); advanceEventTime(700)
            moveBy(Offset(-30f, -30f)); cancel()
        }
        rule.waitForIdle()
        assertEquals(moved, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(1, store.writes.size); assertEquals(0, clicks) }
        rule.runOnIdle { store.fabPosition.value = null }
        rule.waitForIdle()
        assertEquals(initial, fab().fetchSemanticsNode().boundsInRoot)
    }
    @Test fun failedSaveRestoresSavedPosition() {
        store.fail = true
        show()
        val initial = fab().fetchSemanticsNode().boundsInRoot
        fab().performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(-80f, -80f)); up()
        }
        rule.waitForIdle()
        assertEquals(initial, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(0, clicks); assertTrue(store.writes.isEmpty()) }
    }

    @Test fun rtlResizeAndTabReturnReprojectWithoutSaving() {
        val width = mutableStateOf(300.dp)
        val visible = mutableStateOf(true)
        store.fabPosition.value = FabPosition(0f, .5f)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.size(width.value, 400.dp).testTag("area")) {
                        DraggableComposeFab(store, { clicks++ }, visible = visible.value)
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = fab().fetchSemanticsNode().boundsInRoot
        val parentBefore = rule.onNodeWithTag("area").fetchSemanticsNode().boundsInRoot
        assertTrue(before.right < parentBefore.right)
        assertTrue(before.left > parentBefore.center.x)
        rule.runOnIdle { width.value = 220.dp }
        rule.waitForIdle()
        val resized = fab().fetchSemanticsNode().boundsInRoot
        assertTrue(resized.left < before.left)
        rule.runOnIdle { visible.value = false }
        fab().assertDoesNotExist()
        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        assertEquals(resized, fab().fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertTrue(store.writes.isEmpty()) }
    }
}
