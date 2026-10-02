package io.github.ponpokoo.mastodonclient

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.feature.web.WebLinkLauncher
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Requires tools/web-browser-fixture.mjs and adb reverse tcp:8765 tcp:8765. */
class CustomTabsBrowserDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Before
    fun enableBrowserViewIds() {
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
    }

    @Test
    fun browserNavigationIntentLaunchAndFallbackReturnToTheCallingScreen() {
        val base = InstrumentationRegistry.getArguments().getString("webTestBaseUrl")
        assumeNotNull(base)
        compose.setContent {
            val context = LocalContext.current
            Button(onClick = { assertTrue(WebLinkLauncher(context).open(base!!.toUri(), true)) }) {
                Text("Open browser test")
            }
        }
        compose.onNodeWithText("Open browser test").performClick()
        try {
            waitForText("Nagisa browser fixture")
            assertEquals("com.android.chrome", automation.rootInActiveWindow?.packageName?.toString())
            assertNotNull(findNode(automation.rootInActiveWindow) { it.viewIdResourceName?.endsWith("/close_button") == true })
            tapText("Next page")
            waitForText("Second browser page")
            pressBack()
            waitForText("Nagisa browser fixture")
            tapText("Open YouTube app")
            waitUntil { automation.rootInActiveWindow?.packageName?.toString() == "com.google.android.youtube" }
            pressBack()
            waitForText("Nagisa browser fixture")
            tapText("Open missing app")
            waitForText("Browser fallback reached")
            val close = findNode(automation.rootInActiveWindow) { it.viewIdResourceName?.endsWith("/close_button") == true }
            assertNotNull(close)
            tap(close!!)
            waitForText("Open browser test")
        } finally {
            // Keep later tests independent even if a browser or app assertion fails.
            repeat(3) { if (automation.rootInActiveWindow?.packageName?.toString() != "io.github.ponpokoo.mastodonclient") pressBack() }
        }
    }

    private fun waitForText(text: String) = waitUntil {
        findNode(automation.rootInActiveWindow) { it.text?.toString() == text } != null
    }

    private fun tapText(text: String) {
        waitForText(text)
        tap(findNode(automation.rootInActiveWindow) { it.text?.toString() == text }!!)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    private fun pressBack() {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            automation.injectInputEvent(KeyEvent(time, SystemClock.uptimeMillis(), action,
                KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD), false)
        }
        SystemClock.sleep(300)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(200)
        }
        fail("Browser did not reach the expected screen within 15 seconds")
    }

    private fun findNode(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findNode(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }
}
