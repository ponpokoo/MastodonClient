package io.github.ponpokoo.mastodonclient.feature.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** Opens browsing links only. Page navigation and intent: links belong to the browser. */
internal class WebLinkLauncher(
    private val context: Context,
    private val customTabsPackage: () -> String? = { findCustomTabsPackage(context) },
) {
    fun open(uri: Uri, inApp: Boolean): Boolean {
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false

        if (inApp) {
            val browserPackage = customTabsPackage()
            if (browserPackage != null) {
                val tab = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                    // Do not opt initial redirects into external default handling.
                    // The browser owns the policy for subsequent navigation.
                    .setSendToExternalDefaultHandlerEnabled(false)
                    .build()
                tab.intent.setPackage(browserPackage)
                // Use the calling Activity's task so closing the tab returns to the same screen.
                if (tryStart { tab.launchUrl(context, uri) }) return true
            }
        }

        // Non-supporting browsers and unavailable providers use Android's normal URL handling.
        return tryStart {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    private fun tryStart(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Suppress("DEPRECATION")
private fun findCustomTabsPackage(context: Context): String? {
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val candidates = context.packageManager
        .queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
        .distinct()
    // AndroidX prefers the user's default browser, then another supporting browser.
    return CustomTabsClient.getPackageName(context, candidates)
}
