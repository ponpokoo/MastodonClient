package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.data.local.AppLicensesLocalDataSource
import io.github.ponpokoo.mastodonclient.data.repository.DefaultAppLicensesRepository
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.LicensesViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.feature.settings.licenseTextBlocks
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class LicensesDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun licenseRowOpensPlainBundledTextAndLongNoticesCanBeScrolled() {
        val preferences = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        })
        val models = ViewModelStore()
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        }).also { models.put("maintenance", it) }
        val licenses = LicensesViewModel(DefaultAppLicensesRepository(AppLicensesLocalDataSource(rule.activity)))
            .also { models.put("licenses", it) }
        val theme = mutableStateOf(ThemeMode.Light)
        try {
            rule.setContent {
                MastodonClientTheme(themeMode = theme.value) {
                    SettingsScreen(preferences, null, emptyList(), maintenance, {}, {}, {}, licenses = licenses)
                }
            }
            rule.onNodeWithTag("settings_category_About").performScrollTo().performClick()
            rule.onNodeWithTag("settings_licenses").performClick()
            rule.waitUntil(5_000) { licenses.uiState.value.notices.isNotEmpty() }
            rule.onNodeWithTag("license_text_nagisa_0").assertIsDisplayed()
            capture("licenses-text-light")
            val blocks = licenseTextBlocks(licenses.uiState.value.notices)
            val reorderable = licenses.uiState.value.notices.single { it.id.startsWith("sh.calvin.reorderable:") }
            val names = blocks.single { it.id.startsWith("license_names_") && it.text.contains(reorderable.title) }
            rule.onNodeWithTag("license_document").performScrollToNode(hasTestTag(names.id))
            rule.onNodeWithTag(names.id).assertTextContains(reorderable.title, substring = true)
            val sdk = licenses.uiState.value.notices.single { it.id.startsWith("com.google.android.gms:play-services-base:") }
            val groupId = licenses.uiState.value.notices.first { it.text == sdk.text }.id
            val lastChunk = blocks.last { it.id.startsWith("license_text_${groupId}_") }
            rule.onNodeWithTag("license_document").performScrollToNode(hasTestTag(lastChunk.id))
            rule.onNodeWithTag(lastChunk.id).assertIsDisplayed()
            capture("licenses-notice-end")
            rule.onNodeWithContentDescription("戻る").performClick()
            rule.onNodeWithTag("settings_licenses").assertIsDisplayed()
            rule.runOnIdle { theme.value = ThemeMode.Dark }
            rule.onNodeWithTag("settings_licenses").performClick()
            rule.onNodeWithTag("license_text_nagisa_0").assertIsDisplayed()
            capture("licenses-text-dark")
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("settings_licenses").assertIsDisplayed()
        } finally { rule.runOnIdle { models.clear() } }
    }

    private fun capture(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
