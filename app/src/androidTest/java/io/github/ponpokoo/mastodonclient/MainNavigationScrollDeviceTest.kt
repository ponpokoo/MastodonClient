package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Velocity
import io.github.ponpokoo.mastodonclient.feature.timeline.MainNavigationScrollState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class MainNavigationScrollDeviceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var navigation: MainNavigationScrollState
    private lateinit var header: TopAppBarState
    private lateinit var connection: NestedScrollConnection
    private lateinit var scope: CoroutineScope
    private val canScroll = mutableStateOf(true)

    @Test fun bothBarsTrackDirectInputWithoutConsumingItAndIgnoreFlingDeltas() {
        show()
        rule.runOnIdle {
            drag(-30f)
            assertEquals(0.3f, navigation.hiddenFraction, 0.001f)
            assertEquals(0.3f, header.collapsedFraction, 0.001f)
            assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.SideEffect))
            assertEquals(Offset.Zero,
                connection.onPostScroll(Offset(0f, -10f), Offset.Zero, NestedScrollSource.SideEffect))
            assertEquals(0.3f, header.collapsedFraction, 0.001f)
            assertEquals(0.3f, navigation.hiddenFraction, 0.001f)
            drag(10f)
            assertEquals(0.2f, navigation.hiddenFraction, 0.001f)
        }
    }

    @Test fun releaseSettlesToTheNearestEndWithoutWaitingForOrConsumingTheListFling() {
        show()
        rule.mainClock.autoAdvance = false
        for (fraction in listOf(0.3f, 0.7f)) {
            for (velocity in listOf(-8000f, 0f, 8000f)) {
                var released = false
                rule.runOnIdle {
                    navigation.show()
                    drag(-fraction * 100f)
                    scope.launch {
                        assertEquals(Velocity.Zero, connection.onPreFling(Velocity(0f, velocity)))
                        released = true
                    }
                }
                rule.runOnIdle { assertTrue("Footer settling must not delay the list fling", released) }
                // No post-fling callback: the child's inertia can still be running.
                rule.mainClock.advanceTimeBy(240)
                rule.runOnIdle {
                    assertEquals(if (fraction < 0.5f) 0f else 1f, navigation.hiddenFraction, 0.001f)
                }
            }
        }
    }

    @Test fun aNewDragInterruptsSettlingFromItsCurrentPosition() {
        show()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            drag(-70f)
            scope.launch { connection.onPreFling(Velocity.Zero) }
        }
        rule.mainClock.advanceTimeBy(48)
        var interrupted = 0f
        rule.runOnIdle {
            val before = navigation.hiddenFraction
            assertTrue(before > 0.7f && before < 1f)
            drag(20f)
            interrupted = navigation.hiddenFraction
            assertEquals(before - 0.2f, interrupted, 0.001f)
        }
        rule.mainClock.advanceTimeBy(240)
        rule.runOnIdle { assertEquals(interrupted, navigation.hiddenFraction, 0.001f) }
    }

    @Test fun showingTheBarCancelsSettlingAndLockedInputDoesNotHideIt() {
        show()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            drag(-70f)
            scope.launch { connection.onPreFling(Velocity.Zero) }
        }
        rule.mainClock.advanceTimeBy(48)
        rule.runOnIdle {
            navigation.show()
            canScroll.value = false
        }
        rule.mainClock.advanceTimeBy(240)
        rule.runOnIdle {
            drag(-100f)
            assertEquals(0f, navigation.hiddenFraction, 0.001f)
        }
    }

    @Test fun restorationPreservesTheSharedBarPosition() {
        val restoration = show()
        rule.runOnIdle {
            drag(-30f)
            connection.onPreScroll(Offset(0f, -70f), NestedScrollSource.SideEffect)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertEquals(0.3f, navigation.hiddenFraction, 0.001f)
            assertEquals(0.3f, header.collapsedFraction, 0.001f)
        }
    }

    private fun drag(deltaY: Float) {
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, deltaY), NestedScrollSource.UserInput))
    }

    private fun show(): StateRestorationTester = StateRestorationTester(rule).also { restoration ->
        restoration.setContent {
            header = rememberTopAppBarState(initialHeightOffsetLimit = -100f)
            navigation = remember(header) { MainNavigationScrollState(header) }
            scope = rememberCoroutineScope()
            connection = navigation.connection(
                scope,
                canScroll = { canScroll.value },
            )
            Box {}
        }
    }
}
