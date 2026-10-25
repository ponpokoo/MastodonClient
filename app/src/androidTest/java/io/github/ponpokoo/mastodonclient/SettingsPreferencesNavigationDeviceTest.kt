package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AutoplayPolicy
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class SettingsPreferencesNavigationDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loadedPreferencesSurvivePageNavigationSavingAndSettingsReentry() {
        val subscriptions = AtomicInteger()
        val lateReaders = CompletableDeferred<Unit>()
        val data = object : DataStore<Preferences> {
            val saved = MutableStateFlow(emptyPreferences())
            override val data = flow {
                // The host already loaded settings. New page collectors must not be needed
                // to replace default values, even when their first emission is delayed.
                if (subscriptions.incrementAndGet() > 1) lateReaders.await()
                emitAll(saved)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(saved.value).also { saved.value = it }
        }
        val store = UserPreferencesStore(data)
        val account = AccountSession("a", "https://one.example", "me", "me", "Me", "", "unused")
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        })
        runBlocking {
            store.setThemeMode(ThemeMode.Dark)
            store.setGifAutoplay(AutoplayPolicy.Never)
            store.setExpandSpoilers(account.sessionId, true)
        }
        var settingsVisible by mutableStateOf(true)
        rule.setContent {
            val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
            MastodonClientTheme(preferences.themeMode) {
                if (settingsVisible) {
                    SettingsScreen(
                        store = store,
                        preferences = preferences,
                        activeSession = account,
                        sessions = listOf(account),
                        maintenance = maintenance,
                        onBack = { settingsVisible = false },
                        onLogout = {},
                        onAddAccount = {},
                    )
                } else {
                    TextButton(onClick = { settingsVisible = true }) { Text("設定を開く") }
                }
            }
        }

        openPage("Appearance")
        rule.onNodeWithText("ダーク").assertIsDisplayed()
        rule.onNodeWithText("テーマ").performClick()
        rule.onNode(hasText("ライト") and hasAnyAncestor(isPopup())).performClick()
        rule.onNodeWithText("ライト").assertIsDisplayed()
        back()

        openPage("Media")
        rule.onNodeWithText("GIFの自動再生").assertIsDisplayed()
        rule.onNode(isToggleable()).assertIsOn()
        rule.onAllNodesWithText("再生しない").assertCountEquals(2)
        rule.onNodeWithText("内容警告(CW)を常に展開する").performClick()
        rule.onNode(isToggleable()).assertIsOff()
        // External changes must also reach the currently displayed page.
        runBlocking { store.setExpandSpoilers(account.sessionId, true) }
        rule.onNode(isToggleable()).assertIsOn()
        back()
        back()

        rule.onNodeWithText("設定を開く").performClick()
        openPage("Appearance")
        rule.onNodeWithText("ライト").assertIsDisplayed()
        back()
        openPage("Media")
        rule.onNode(isToggleable()).assertIsOn()
        rule.onAllNodesWithText("再生しない").assertCountEquals(2)
    }

    private fun openPage(name: String) {
        rule.onNodeWithTag("settings_category_$name").performScrollTo().performClick()
    }

    private fun back() { rule.onNodeWithContentDescription("戻る").performClick() }
}
