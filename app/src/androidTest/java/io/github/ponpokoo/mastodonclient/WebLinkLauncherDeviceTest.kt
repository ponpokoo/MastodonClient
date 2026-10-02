package io.github.ponpokoo.mastodonclient

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.feature.web.WebLinkLauncher
import org.junit.Assert.*
import org.junit.Test

class WebLinkLauncherDeviceTest {
    @Test
    fun inAppLinksUseTheBrowserWithoutCreatingAnotherTask() {
        val context = RecordingContext()
        val launcher = WebLinkLauncher(context) { "test.browser" }
        assertTrue(launcher.open("https://example.org/page".toUri(), inApp = true))
        val launched = context.intents.single()
        assertEquals("test.browser", launched.`package`)
        assertEquals("https://example.org/page", launched.dataString)
        assertTrue(launched.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertFalse(launched.getBooleanExtra(CustomTabsIntent.EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER, false))
        assertEquals(0, launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun disablingInAppBrowsingUsesNormalUrlHandlingWithoutDiscoveringProviders() {
        val context = RecordingContext()
        val launcher = WebLinkLauncher(context) { error("Provider discovery must not run") }
        assertTrue(launcher.open("https://example.org/page".toUri(), inApp = false))
        val launched = context.intents.single()
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertFalse(launched.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertNull(launched.`package`)
    }

    @Test
    fun httpLinksCanUseCustomTabsWithoutAnAppCleartextException() {
        val context = RecordingContext()
        assertTrue(WebLinkLauncher(context) { "test.browser" }.open("http://example.org/page".toUri(), true))
        assertEquals("http://example.org/page", context.intents.single().dataString)
        assertTrue(context.intents.single().hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test
    fun missingCustomTabsSupportFallsBackToNormalUrlHandling() {
        val context = RecordingContext()
        assertTrue(WebLinkLauncher(context) { null }.open("https://example.org".toUri(), true))
        assertFalse(context.intents.single().hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test
    fun anUnavailableOrRestrictedProviderFallsBackWithoutLosingTheUrl() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val context = RecordingContext { intent ->
                if (intent.`package` != null) throw failure
            }
            assertTrue(WebLinkLauncher(context) { "test.browser" }.open("https://example.org/page".toUri(), true))
            assertEquals(2, context.intents.size)
            assertEquals(context.intents.first().data, context.intents.last().data)
            assertFalse(context.intents.last().hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
    }

    @Test
    fun noUrlHandlerReturnsFailureInsteadOfCrashing() {
        val context = RecordingContext { throw ActivityNotFoundException() }
        assertFalse(WebLinkLauncher(context) { "test.browser" }.open("https://example.org".toUri(), true))
    }

    @Test
    fun localUrlsAndIntentUrlsAreNeverLaunchedFromTheMastodonLinkEntryPoint() {
        val context = RecordingContext()
        val launcher = WebLinkLauncher(context) { error("Invalid links must not discover providers") }
        for (url in listOf("https:///page", "file:///private", "content://private/item", "javascript:alert(1)",
            "intent://test/#Intent;scheme=test;end")) {
            assertFalse(launcher.open(url.toUri(), true))
        }
        assertTrue(context.intents.isEmpty())
    }

    private class RecordingContext(
        private val onStart: (Intent) -> Unit = {},
    ) : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val intents = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) = record(intent)
        override fun startActivity(intent: Intent, options: Bundle?) = record(intent)
        private fun record(intent: Intent) {
            intents += Intent(intent)
            onStart(intent)
        }
    }
}
