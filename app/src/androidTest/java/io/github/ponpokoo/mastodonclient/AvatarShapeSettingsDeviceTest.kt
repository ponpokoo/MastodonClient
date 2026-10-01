package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPage
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsPageContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class AvatarShapeSettingsDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shapeChoiceUpdatesPreviewAndResetRestoresCircle() {
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
            MaterialTheme(colorScheme = lightColorScheme(surface = Color.Red, background = Color.White)) {
                SettingsPageContent(SettingsPage.Timeline, {}, maintenance, store, null, emptyList(), {}, {}, {})
            }
        }
        assertAvatarCorner(isSquare = false)
        rule.onNodeWithText("ユーザーアイコンの形").performScrollTo().performClick()
        rule.onNodeWithText("四角").performClick()
        rule.runOnIdle {
            assertEquals(AvatarIconShape.Square, runBlocking {
                UserPreferencesStore(dataStore).preferences.first().timelineDisplay.avatarIconShape
            })
        }
        assertAvatarCorner(isSquare = true)
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasText("表示設定を初期値に戻す"))
        rule.onNodeWithText("表示設定を初期値に戻す").performClick()
        rule.runOnIdle {
            assertEquals(AvatarIconShape.Circle, runBlocking { store.preferences.first().timelineDisplay.avatarIconShape })
        }
        assertAvatarCorner(isSquare = false)
    }

    private fun assertAvatarCorner(isSquare: Boolean) {
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("status_author_avatar"))
        val pixels = rule.onNodeWithTag("status_author_avatar").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        val corner = pixels[2, 2]
        if (isSquare) assertEquals(center, corner) else assertNotEquals(center, corner)
    }
}
