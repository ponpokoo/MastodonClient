package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.ponpokoo.mastodonclient.feature.common.NewItemsNoticeEffect
import io.github.ponpokoo.mastodonclient.feature.common.NewItemsNoticeState
import io.github.ponpokoo.mastodonclient.feature.common.TimelineNoticeStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File

class NewItemsNoticeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingCountStaysFixedAndDisappearsOnlyAfterArrival() {
        val state = NewItemsNoticeState()
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                NewItemsNoticeEffect(state, true)
                TimelineNoticeStack(state.notice, SnackbarHostState(), { clicks++ })
            }
        }
        compose.runOnIdle { state.update(6, false) }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithText("↑ 新着6件").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, clicks); state.update(9, false) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("↑ 新着6件").assertExists()
        compose.runOnIdle { state.update(0, true) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("↑ 新着6件").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("↑ 新着6件").assertDoesNotExist()
    }

    @Test fun atTopNoticeLastsForItsOwnTimerWhenAnotherResultArrives() {
        val state = NewItemsNoticeState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                NewItemsNoticeEffect(state, true)
                TimelineNoticeStack(state.notice, SnackbarHostState(), {})
            }
        }
        compose.runOnIdle { state.update(0, true, resultKey = 1, resultCount = 2) }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("新着2件").assertExists()
        compose.runOnIdle { state.update(0, true, resultKey = 2, resultCount = 3) }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithText("新着3件").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("新着3件").assertDoesNotExist()
    }

    @Test fun errorAndPendingNoticeAreBothVisibleAndNoticeRemainsActionable() {
        errorScenario(dark = false)
    }

    @Test fun darkErrorAndPendingNoticeAreBothVisibleAndNoticeRemainsActionable() {
        errorScenario(dark = true)
    }

    @Test fun recreatedContentRetainsFrozenCountWhileActualPendingCountHasGrown() {
        val restoration = StateRestorationTester(compose)
        val pending = mutableStateOf(6)
        restoration.setContent {
            val state = rememberSaveable(saver = NewItemsNoticeState.Saver) { NewItemsNoticeState() }
            LaunchedEffect(pending.value) { state.update(pending.value, false) }
            MaterialTheme { TimelineNoticeStack(state.notice, SnackbarHostState(), {}) }
        }
        compose.onNodeWithText("↑ 新着6件").assertExists()
        compose.runOnIdle { pending.value = 9 }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("↑ 新着6件").assertExists()
        compose.onNodeWithText("↑ 新着9件").assertDoesNotExist()
    }

    private fun errorScenario(dark: Boolean) {
        val state = NewItemsNoticeState().apply { update(3, false) }
        val snackbar = SnackbarHostState()
        var clicked = false
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                LaunchedEffect(Unit) { snackbar.showSnackbar("更新に失敗しました", duration = SnackbarDuration.Indefinite) }
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { TimelineNoticeStack(state.notice, snackbar, { clicked = true }) }
                }
            }
        }
        val error = compose.onNodeWithText("更新に失敗しました").fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithText("↑ 新着3件").fetchSemanticsNode().boundsInRoot
        assertTrue(error.bottom < notice.top)
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "new-items-notice-${if (dark) "dark" else "light"}.png")
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("↑ 新着3件").performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}
