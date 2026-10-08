package io.github.ponpokoo.mastodonclient

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyPolicySettingsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun opensPolicyFromSettingsAndHonorsBrowserPreference() {
        val store = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(rule.activity) {
            override fun startActivity(intent: Intent) { intents += intent }
            override fun startActivity(intent: Intent, options: Bundle?) { intents += intent }
        }
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        })
        runBlocking { store.setOpenLinksInApp(false) }
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MastodonClientTheme {
                    SettingsScreen(store, null, emptyList(), maintenance, {}, {}, {})
                }
            }
        }
        rule.onNodeWithTag("settings_category_About").performScrollTo().performClick()
        rule.onNodeWithTag("settings_privacy_policy").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("https://ponpokoo.github.io/nagisa-policy/", intents.single().dataString)
            assertEquals(Intent.ACTION_VIEW, intents.single().action)
            assertFalse(intents.single().hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
        runBlocking { store.setOpenLinksInApp(true) }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_privacy_policy").performClick()
        rule.runOnIdle {
            assertEquals(2, intents.size)
            assertEquals(intents.first().data, intents.last().data)
            // This emulator has a Custom Tabs browser; no actual network navigation is needed.
            assertTrue(intents.last().hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
    }
}
