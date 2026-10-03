package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThumbnailSize
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPage
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPageContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThumbnailSettingsDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bothChoicesSaveAndResetRestoresStandard() {
        val dataStore = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val store = UserPreferencesStore(dataStore)
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        })
        rule.setContent {
            MaterialTheme {
                SettingsPageContent(SettingsPage.Timeline, {}, maintenance, store, null, emptyList(), {}, {}, {})
            }
        }
        rule.runOnIdle {
            assertEquals(ThumbnailSize.Standard, runBlocking { store.preferences.first().timelineDisplay.thumbnailSize })
        }
        rule.onNodeWithText("サムネイルサイズ").performScrollTo().performClick()
        choice("大").assertDoesNotExist()
        choice("小").assertExists()
        choice("標準").performClick()
        rule.runOnIdle {
            assertEquals(ThumbnailSize.Standard, runBlocking {
                UserPreferencesStore(dataStore).preferences.first().timelineDisplay.thumbnailSize
            })
        }
        rule.onNodeWithText("サムネイルサイズ").performClick()
        choice("小").performClick()
        rule.runOnIdle {
            assertEquals(ThumbnailSize.Compact, runBlocking {
                UserPreferencesStore(dataStore).preferences.first().timelineDisplay.thumbnailSize
            })
        }
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasText("表示設定を初期値に戻す"))
        rule.onNodeWithText("表示設定を初期値に戻す").performClick()
        rule.runOnIdle {
            assertEquals(ThumbnailSize.Standard, runBlocking {
                UserPreferencesStore(dataStore).preferences.first().timelineDisplay.thumbnailSize
            })
        }
    }

    private fun choice(label: String) = rule.onNode(hasText(label) and hasAnyAncestor(isPopup()))
}
