package io.github.ponpokoo.mastodonclient.releasechecks

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Black-box checks against the installed, optimized Release APK on a logged-out test device. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun launchRelease() {
        val application = context.packageManager.getApplicationInfo(APP_PACKAGE, 0)
        assertEquals("The tested app must be a non-debuggable Release build", 0,
            application.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        if (Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant $APP_PACKAGE android.permission.POST_NOTIFICATIONS")
        }
        coldStart()
    }

    @Test
    fun coldStartAcceptsInputOnLoginScreen() {
        input().text = "example.test"
        await(By.text(CONNECT).enabled(true))
    }

    @Test
    fun invalidServerIsRejectedAndEditingAllowsRetry() {
        input().text = "http://example.test"
        await(By.text(CONNECT).enabled(true)).click()
        await(By.textStartsWith(INVALID_SERVER))

        input().text = "example.test"
        assertTrue(device.wait(Until.gone(By.textStartsWith(INVALID_SERVER)), UI_TIMEOUT))
        await(By.text(CONNECT).enabled(true))
    }

    @Test
    fun processRestartResetsTransientLoginInput() {
        input().text = "http://example.test"
        await(By.text(CONNECT).enabled(true)).click()
        await(By.textStartsWith(INVALID_SERVER))

        coldStart()
        assertEquals("", input().text)
        assertTrue(device.wait(Until.gone(By.textStartsWith(INVALID_SERVER)), UI_TIMEOUT))
    }

    @Test
    fun notificationForMissingAccountKeepsLoginUsable() {
        input().text = "http://example.test"
        context.startActivity(launchIntent().apply {
            action = "$APP_PACKAGE.OPEN_NOTIFICATION"
            data = Uri.parse("mastodonclient-notification://open/missing-account/000001234567890123")
            putExtra("notification_session_id", "missing-account")
            putExtra("notification_id", "000001234567890123")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        device.waitForIdle()
        assertEquals("http://example.test", input().text)
        await(By.text(CONNECT).enabled(true)).click()
        await(By.textStartsWith(INVALID_SERVER))
    }

    @Test
    fun discoversConfiguredPublicInstanceWithoutAuthentication() {
        val server = InstrumentationRegistry.getArguments().getString("serverDomain")?.trim()
        assumeTrue("Set -Pnagisa.releaseTestServer to run the public API check", !server.isNullOrEmpty())
        input().text = requireNotNull(server)
        await(By.text(CONNECT).enabled(true)).click()
        await(By.textEndsWith(" に接続できました。"), NETWORK_TIMEOUT)
        await(By.text("ブラウザでログイン").enabled(true))
    }

    private fun coldStart() {
        device.executeShellCommand("am force-stop $APP_PACKAGE")
        context.startActivity(launchIntent().apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        assertTrue("Use a dedicated, logged-out test device; login screen did not appear",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE).text(CONNECT)), UI_TIMEOUT))
        input()
    }

    private fun launchIntent(): Intent = requireNotNull(
        context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
    ) { "Install the signed Release APK before running these checks" }

    private fun input(): UiObject2 = await(By.pkg(APP_PACKAGE).clazz("android.widget.EditText"))

    private fun await(selector: BySelector, timeout: Long = UI_TIMEOUT): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), timeout)) {
            "Release UI did not reach the expected state: $selector"
        }

    private companion object {
        const val APP_PACKAGE = "io.github.ponpokoo.mastodonclient"
        const val CONNECT = "接続を確認"
        const val INVALID_SERVER = "HTTPSのサーバードメインを入力してください。"
        const val UI_TIMEOUT = 20_000L
        const val NETWORK_TIMEOUT = 60_000L
    }
}
