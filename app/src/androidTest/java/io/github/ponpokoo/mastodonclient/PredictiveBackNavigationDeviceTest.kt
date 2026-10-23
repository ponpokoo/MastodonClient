package io.github.ponpokoo.mastodonclient

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.test.filters.SdkSuppress
import io.github.ponpokoo.mastodonclient.navigation.ScreenNavHost
import io.github.ponpokoo.mastodonclient.navigation.animatedScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@SdkSuppress(minSdkVersion = 34)
class PredictiveBackNavigationDeviceTest(
    private val typedRoutes: Boolean,
    private val edge: Int,
    private val darkBackground: Boolean,
) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var navigation: NavHostController
    private lateinit var middleList: LazyListState
    private val backdropColor get() = if (darkBackground) Color(0xFF1E1E24) else Color.Green

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "typedRoutes={0}, edge={1}, darkBackground={2}")
        fun routes() = listOf(false, true).flatMap { typed ->
            listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT).flatMap { edge ->
                listOf(false, true).map { dark -> arrayOf<Any>(typed, edge, dark) }
            }
        }
    }

    @Serializable data object Root
    @Serializable data object Middle
    @Serializable data object Detail

    @Test fun shortSlideAndFadeKeepBackgroundFixedAndCancelRestoresInputThenBackRestoresList() {
        rule.setContent {
            MaterialTheme {
                navigation = rememberNavController()
                ScreenNavHost(navigation, if (typedRoutes) Root else "root", Modifier.fillMaxSize()) {
                    if (typedRoutes) {
                        animatedScreen<Root> { TestScreen("root", Color.Blue) }
                        animatedScreen<Middle> { MiddleScreen() }
                        animatedScreen<Detail> { TestScreen("detail", Color.Red) }
                    } else {
                        animatedScreen("root") { TestScreen("root", Color.Blue) }
                        animatedScreen("middle") { MiddleScreen() }
                        animatedScreen("detail") { TestScreen("detail", Color.Red) }
                    }
                }
            }
        }
        rule.runOnIdle { if (typedRoutes) navigation.navigate(Middle) else navigation.navigate("middle") }
        rule.waitForIdle()
        rule.runOnIdle { runBlocking { middleList.scrollToItem(12, 23) } }
        val originalPosition = rule.runOnIdle { middleList.firstVisibleItemIndex to middleList.firstVisibleItemScrollOffset }
        val viewport = rule.onNodeWithTag("middle").getUnclippedBoundsInRoot()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { if (typedRoutes) navigation.navigate(Detail) else navigation.navigate("detail") }
        rule.mainClock.advanceTimeBy(64)
        assertEquals("Forward navigation slid the destination", viewport, rule.onNodeWithTag("detail").getUnclippedBoundsInRoot())
        assertEquals("Forward navigation moved the background", viewport, rule.onNodeWithTag("middle").getUnclippedBoundsInRoot())
        val earlyFade = rule.onRoot().captureToImage().toPixelMap()[2, 2]
        assertTrue("Forward navigation did not blend the screens", earlyFade.red > backdropColor.red && earlyFade.red < 1f)
        rule.mainClock.advanceTimeBy(64)
        val laterFade = rule.onRoot().captureToImage().toPixelMap()[2, 2]
        assertTrue("Forward fade did not reveal the destination progressively", laterFade.red > earlyFade.red)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("detail_edit").performClick()
        val originalBounds = rule.onNodeWithTag("detail").fetchSemanticsNode().boundsInRoot
        val sampleX = originalBounds.center.x.toInt()
        val sampleY = (originalBounds.top + originalBounds.height * 0.25f).toInt()

        repeat(2) {
            startGesture()
            var previousMovement = 0f
            var previousRed = 1f
            var movementPerProgress: Float? = null
            for (progress in listOf(0.25f, 0.5f, 0.6f, 0.8f)) {
                progressGesture(progress)
                val bounds = rule.onNodeWithTag("detail").getUnclippedBoundsInRoot()
                assertShortRightSlide(viewport, bounds)
                val movement = bounds.left.value - viewport.left.value
                assertTrue("Slide did not follow gesture progress", movement > previousMovement)
                movementPerProgress?.let {
                    assertEquals("Slide accelerated instead of following gesture progress proportionally", it, movement / progress, 1f)
                }
                movementPerProgress = movement / progress
                assertEquals("Back preview moved the background", viewport, rule.onNodeWithTag("middle").getUnclippedBoundsInRoot())
                val pixels = rule.onRoot().captureToImage().toPixelMap()
                assertEquals("Background was dimmed or faded", backdropColor.toArgb(), pixels[2, sampleY].toArgb())
                val foreground = pixels[sampleX, sampleY]
                if (progress < 0.55f) {
                    assertEquals("Foreground faded before the delayed fade should start", Color.Red.toArgb(), foreground.toArgb())
                } else {
                    assertTrue("Foreground did not fade over the previous screen", foreground.red > backdropColor.red && foreground.red < previousRed)
                }
                previousMovement = movement
                previousRed = foreground.red
            }
            progressGesture(0.9f)
            assertEquals("Foreground should disappear before the slide finishes", backdropColor.toArgb(),
                rule.onRoot().captureToImage().toPixelMap()[sampleX, sampleY].toArgb())
            progressGesture(1f)
            val endBounds = rule.onNodeWithTag("detail").getUnclippedBoundsInRoot()
            assertShortRightSlide(viewport, endBounds)
            assertEquals("Slide should end at 35% of the screen width", (viewport.right - viewport.left).value * 0.35f,
                (endBounds.left - viewport.left).value, 1f)
            assertEquals("Previous screen should be revealed at the end", backdropColor.toArgb(),
                rule.onRoot().captureToImage().toPixelMap()[sampleX, sampleY].toArgb())
            // Also cancel after the foreground has fully faded to verify complete restoration.
            progressGesture(if (it == 0) 0.6f else 0.95f)
            rule.runOnIdle { assertTrue(if (typedRoutes) navigation.currentDestination!!.hasRoute<Detail>() else navigation.currentDestination!!.route == "detail") }
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.onNodeWithText("detail: 1").assertIsDisplayed()
            assertEquals(viewport, rule.onNodeWithTag("detail").getUnclippedBoundsInRoot())
            val restored = rule.onRoot().captureToImage().toPixelMap()
            assertEquals("Fade remained after cancellation", Color.Red.toArgb(), restored[sampleX, sampleY].toArgb())
            assertEquals("Clipping remained after cancellation", Color.Red.toArgb(), restored[2, 2].toArgb())
        }

        startGesture()
        progressGesture(0.6f)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("middle").assertIsDisplayed()
        rule.onNodeWithTag("detail").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(originalPosition, middleList.firstVisibleItemIndex to middleList.firstVisibleItemScrollOffset)
            assertTrue(if (typedRoutes) navigation.currentDestination!!.hasRoute<Middle>() else navigation.currentDestination!!.route == "middle")
            assertFalse(rule.activity.isFinishing)
        }
        // Button/three-button back uses the same short slide without moving the destination.
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(64)
        assertShortRightSlide(viewport, rule.onNodeWithTag("middle").getUnclippedBoundsInRoot())
        assertEquals(viewport, rule.onNodeWithTag("root").getUnclippedBoundsInRoot())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("root").assertIsDisplayed()
        rule.runOnIdle { assertFalse(rule.activity.isFinishing) }
    }

    private fun assertShortRightSlide(viewport: DpRect, bounds: DpRect) {
        val viewportWidth = (viewport.right - viewport.left).value
        assertEquals("Transition scaled the screen width", viewportWidth, (bounds.right - bounds.left).value, 0.5f)
        assertEquals("Transition scaled the screen height", (viewport.bottom - viewport.top).value, (bounds.bottom - bounds.top).value, 0.5f)
        assertEquals("Horizontal slide shifted vertically", viewport.top.value, bounds.top.value, 0.5f)
        val movement = bounds.left.value - viewport.left.value
        assertTrue("Expected a short slide to the right", movement > 0f && movement < viewportWidth / 2f)
    }

    private fun startGesture() {
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, edge))
        }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
    }

    private fun progressGesture(progress: Float) {
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, progress, edge))
        }
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
    }

    @Composable
    private fun MiddleScreen() {
        middleList = rememberLazyListState()
        LazyColumn(state = middleList, contentPadding = PaddingValues(horizontal = 24.dp),
            modifier = Modifier.fillMaxSize().background(backdropColor).testTag("middle")) {
            items((0..99).toList()) { Text("row $it") }
        }
    }

    @Composable
    private fun TestScreen(tag: String, color: Color) {
        var edits by rememberSaveable { mutableIntStateOf(0) }
        Box(Modifier.fillMaxSize().background(color).testTag(tag), contentAlignment = Alignment.Center) {
            Column {
                Text("$tag: $edits")
                TextButton(onClick = { edits++ }, modifier = Modifier.testTag("${tag}_edit")) { Text("Edit") }
            }
        }
    }
}
