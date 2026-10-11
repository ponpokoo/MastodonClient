package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.feature.common.LocalReadingSettings
import io.github.ponpokoo.mastodonclient.feature.common.ReadingSettings
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReadingSettingsDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun mediaAndCwChoicesPersistPerAccountIndependentlyOfServerCacheUpdates() {
        val data = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val store = UserPreferencesStore(data)
        val account = AccountSession("a", "https://a.example/", "account-a", "alice", "Alice", "", "token-a")
        val other = account.copy(sessionId = "b", accountId = "account-b")
        val active = mutableStateOf(account)
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        })
        rule.setContent {
            MaterialTheme {
                SettingsPageContent(SettingsPage.Media, {}, maintenance, store, active.value,
                    listOf(account, other), {}, {}, {})
            }
        }
        val labels = listOf("閲覧注意としてマークされたメディアは隠す", "メディアを常に表示する", "メディアを常に隠す")
        for ((media, label) in MediaDisplay.entries.zip(labels)) {
            rule.onNodeWithText("メディアの表示設定").performClick()
            rule.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
            rule.runOnIdle {
                assertEquals(media, runBlocking { UserPreferencesStore(data).preferences.first().forAccount("a").mediaDisplay })
            }
        }
        rule.onNodeWithText("CWを常に展開する").performClick()
        rule.runOnIdle {
            assertEquals(true, runBlocking { UserPreferencesStore(data).preferences.first().forAccount("a").expandSpoilers })
            active.value = other
        }
        rule.onNodeWithText(labels[0]).assertExists()
        rule.runOnIdle {
            assertEquals(false, runBlocking { store.preferences.first().forAccount("b").expandSpoilers })
            active.value = account
        }
        rule.onNodeWithText(labels[2]).assertExists()
        rule.runOnIdle {
            runBlocking {
                store.saveServerSettings("a", ServerAccountSettings(account.instanceUrl, account.accountId, defaults = PostingDefaults(visibility = "private")))
            }
        }
        rule.onNodeWithText(labels[2]).assertExists()
        rule.onNode(isToggleable()).assertIsOn()
        rule.onNodeWithText("CWを常に展開する").performClick()
        rule.runOnIdle {
            assertEquals(false, runBlocking { UserPreferencesStore(data).preferences.first().forAccount("a").expandSpoilers })
        }
    }

    @Test fun mediaPoliciesApplyToSensitiveAndOrdinaryMediaWithManualRevealAndRefolding() {
        val current = mutableStateOf(post)
        val reading = mutableStateOf(ReadingSettings("a", ReadingPreferences(MediaDisplay.Default, false)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReadingSettings provides reading.value) {
                    StatusCard(current.value, null, onUnavailableAction = {})
                }
            }
        }
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.onNodeWithText("閲覧注意のメディアを表示").performClick()
        rule.onNodeWithTag("media_attachment").assertExists()
        rule.onNodeWithText("閲覧注意に戻す").performClick()
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.runOnIdle { reading.value = reading.value.copy(preferences = ReadingPreferences(MediaDisplay.ShowAll, false)) }
        // The manual hide survives a changed preference on this card.
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.runOnIdle { current.value = post.copy(statusId = "new") }
        rule.onNodeWithTag("media_attachment").assertExists()
        rule.runOnIdle { reading.value = reading.value.copy(preferences = ReadingPreferences(MediaDisplay.HideAll, false)) }
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.runOnIdle { current.value = post.copy(statusId = "ordinary", sensitive = false) }
        rule.onNodeWithText("メディアを表示").performClick()
        rule.onNodeWithTag("media_attachment").assertExists()
        rule.onNodeWithText("メディアを隠す").performClick()
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.runOnIdle { reading.value = ReadingSettings("b", ReadingPreferences(MediaDisplay.Default, false)) }
        rule.onNodeWithTag("media_attachment").assertExists()
    }

    @Test fun cwAndMediaPreferencesRemainIndependentAndNeverBypassServerWarnings() {
        val current = mutableStateOf(post.copy(spoilerText = "注意文"))
        val reading = mutableStateOf(ReadingSettings("a", ReadingPreferences(MediaDisplay.ShowAll, false)))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReadingSettings provides reading.value) {
                    StatusCard(current.value, null, onUnavailableAction = {})
                }
            }
        }
        rule.onNodeWithText("本文").assertDoesNotExist()
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.runOnIdle { reading.value = reading.value.copy(preferences = ReadingPreferences(MediaDisplay.Default, true)) }
        rule.onNodeWithText("本文").assertExists()
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.onNodeWithText("注意文").performClick()
        rule.onNodeWithText("本文").assertDoesNotExist()
        rule.runOnIdle { current.value = current.value.copy(favouritesCount = 1) }
        rule.onNodeWithText("本文").assertDoesNotExist()
        rule.runOnIdle {
            reading.value = ReadingSettings("b", ReadingPreferences(MediaDisplay.ShowAll, true))
            current.value = current.value.copy(filterDisplay = FilterPresentation(warnings = listOf(ServerFilter("warn", "警告", setOf("home")))))
        }
        rule.onNodeWithText("本文").assertDoesNotExist()
        rule.onNodeWithTag("filter_reveal").performClick()
        rule.onNodeWithText("本文").assertExists()
        rule.runOnIdle {
            current.value = current.value.copy(filterDisplay = FilterPresentation(mediaWarnings = listOf(ServerFilter("blur", "画像", setOf("home"), action = "blur"))))
        }
        rule.onNodeWithText("本文").assertExists()
        rule.onNodeWithTag("media_attachment").assertDoesNotExist()
        rule.onNodeWithTag("filter_reveal").performClick()
        rule.onNodeWithTag("media_attachment").assertExists()
    }

    private val post = TimelineStatus("row", "post", "2026-10-12T00:00:00Z",
        StatusAuthor("author", "投稿者", "author@example.org", ""), null,
        "<p>本文</p>", "", true, "public", null, 0, 0, 0,
        mediaAttachments = listOf(MediaAttachment("image", "image", null, null, "画像")))
}
