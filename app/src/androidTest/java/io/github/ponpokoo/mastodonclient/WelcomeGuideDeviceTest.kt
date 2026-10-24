package io.github.ponpokoo.mastodonclient

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WelcomeGuideDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val data = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }
    private val store = UserPreferencesStore(data)
    private val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
        override val versionName = "test"
        override val versionCode = 1L
        override suspend fun clearImageCache() = Unit
    })

    @Test fun firstRunWaitsForAccountAndCompletionSurvivesRecreation() {
        var eligible by mutableStateOf(false)
        var generation by mutableStateOf(0)
        var destination: String? = null
        rule.setContent {
            MastodonClientTheme {
                key(generation) { FirstRunGuide(store, eligible, onSettings = { destination = it }) }
            }
        }
        rule.onNodeWithTag("welcome_guide").assertDoesNotExist()
        rule.runOnIdle { eligible = true }
        rule.onNodeWithTag("welcome_notifications").performClick()
        rule.runOnIdle {
            assertEquals(SettingsPage.Notifications.name, destination)
            assertFalse(runBlocking { store.preferences.first().welcomeGuideCompleted })
        }
        rule.onNodeWithTag("welcome_start").performClick()
        rule.waitUntil { runBlocking { store.preferences.first().welcomeGuideCompleted } }
        rule.runOnIdle { generation++; eligible = false }
        rule.runOnIdle { eligible = true }
        rule.onNodeWithTag("welcome_guide").assertDoesNotExist()
    }

    @Test fun firstRunGuideNavigatesToAllSettingsAndReturnsToGuide() {
        var destination by mutableStateOf<String?>(null)
        rule.setContent { MastodonClientTheme {
            val page = destination
            if (page == null) {
                FirstRunGuide(store, eligible = true, onSettings = { destination = it })
            } else {
                SettingsScreen(store, null, emptyList(), maintenance, { destination = null }, {}, {}, initialPage = page)
            }
        } }
        for ((tag, text) in listOf(
            "welcome_notifications" to "定期確認通知（約15分ごと）",
            "welcome_text_size" to "フォントサイズ",
            "welcome_theme" to "端末の設定に合わせる",
        )) {
            rule.onNodeWithTag(tag).performScrollTo().performClick()
            rule.onNodeWithTag("welcome_guide").assertDoesNotExist()
            rule.onNodeWithText(text).assertExists()
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithTag("welcome_guide").assertIsDisplayed()
        }
        rule.onNodeWithTag("welcome_start").performClick()
        rule.onNodeWithTag("welcome_guide").assertDoesNotExist()
    }

    @Test fun firstRunSettingDestinationKeepsRootAsBackDestination() {
        rule.setContent { MastodonClientTheme {
            SettingsScreen(store, null, emptyList(), maintenance, {}, {}, {}, initialPage = "Appearance")
        } }
        rule.onNodeWithText("端末の設定に合わせる").assertExists()
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithTag("settings_category_Appearance").assertExists()
    }

    @Test fun guideLinksUseConfiguredUrlAndBrowserPreference() {
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(rule.activity) {
            override fun startActivity(intent: Intent) { intents += intent }
            override fun startActivity(intent: Intent, options: Bundle?) { intents += intent }
        }
        runBlocking { store.setOpenLinksInApp(false) }
        var eligible by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MastodonClientTheme {
                    SettingsScreen(store, null, emptyList(), maintenance, {}, {}, {})
                    FirstRunGuide(store, eligible = eligible, onSettings = {})
                }
            }
        }
        rule.onNodeWithTag("settings_usage_guide").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(USAGE_GUIDE_URL, intents.single().dataString)
            assertFalse(intents.single().hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
        runBlocking { store.setOpenLinksInApp(true) }
        rule.runOnIdle { eligible = true }
        rule.onNodeWithTag("welcome_usage").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, intents.size)
            assertEquals(USAGE_GUIDE_URL, intents.last().dataString)
            assertTrue(intents.last().hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
        rule.onNodeWithTag("welcome_guide").assertIsDisplayed()
    }

    @Test fun dialogFitsBothThemes() {
        var mode by mutableStateOf(ThemeMode.Dark)
        rule.setContent {
            MastodonClientTheme(mode) {
                SettingsScreen(store, null, emptyList(), maintenance, {}, {}, {})
                FirstRunGuide(store, eligible = true, onSettings = {})
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            rule.runOnIdle { mode = theme }
            rule.onNodeWithTag("welcome_notifications").assertIsDisplayed()
            rule.onNodeWithTag("welcome_usage").assertIsDisplayed()
            rule.onNodeWithTag("welcome_start").assertIsDisplayed()
            screenshot("welcome-${theme.name.lowercase()}.png")
        }
    }

    /** Also run with Android's font_scale=1.5; Dialog owns a separate platform window. */
    @Test fun completionRemainsReachableWhenBodyNeedsScrolling() {
        rule.setContent { MastodonClientTheme(ThemeMode.Light) {
            SettingsScreen(store, null, emptyList(), maintenance, {}, {}, {})
            FirstRunGuide(store, eligible = true, onSettings = {})
        } }
        rule.onNodeWithTag("welcome_start").assertIsDisplayed()
        rule.onNodeWithTag("welcome_usage").performScrollTo().assertIsDisplayed()
        screenshot("welcome-large-text.png")
        rule.onNodeWithTag("welcome_start").performClick()
        rule.onNodeWithTag("welcome_guide").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idleness does not wait for the platform dialog window's fade animation.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.filesDir, "welcome-guide-screenshots").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
