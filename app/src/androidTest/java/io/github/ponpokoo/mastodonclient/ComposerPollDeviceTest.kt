package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.compose.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposerPollDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun optionsRespectServerLimitsAndDurationAndSettingsCanBeChanged() {
        val account = AccountSession("poll-fixture", "https://fixture.example", "me", "me", "Me", "", "unused")
        val preferences = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        lateinit var vm: ComposePostViewModel
        rule.runOnIdle {
            vm = ComposePostViewModel(null, null, object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override suspend fun getComposerConfiguration(session: AccountSession) = Result.success(
                    ComposerConfiguration(polls = PollConfiguration(3, 50, 300, 604800, true)))
            }, object : AuthRepository {
                override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
                override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
                override suspend fun restoreSession() = account
                override suspend fun logout() = Unit
            }, preferences, {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
            }, logMediaFailure = {})
        }
        try {
            rule.waitUntil { !vm.uiState.value.isLoading }
            rule.runOnIdle { vm.enablePoll() }
            rule.setContent { MastodonClientTheme {
                val state by vm.uiState.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
                    ComposerPollEditor(state, vm)
                }
            } }
            rule.onNodeWithTag("compose_poll_remove_0").assertIsNotEnabled()
            rule.onNodeWithTag("compose_poll_option_0").performTextInput("りんご")
            rule.onNodeWithText("＋ 選択肢を追加").performScrollTo().performClick()
            rule.onNodeWithTag("compose_poll_option_2").assertExists()
            rule.onNodeWithText("＋ 選択肢を追加").assertIsNotEnabled()
            rule.onNodeWithTag("compose_poll_remove_1").performScrollTo().performClick()
            rule.onNodeWithTag("compose_poll_option_2").assertDoesNotExist()
            rule.onNodeWithTag("compose_poll_duration").performScrollTo().performClick()
            rule.onNodeWithText("1時間").performClick()
            rule.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            rule.onAllNodes(isToggleable())[1].performScrollTo().performClick()
            rule.runOnIdle {
                assertEquals("りんご", vm.uiState.value.pollOptions.first())
                assertEquals(3600L, vm.uiState.value.pollExpiresInSeconds)
                assertTrue(vm.uiState.value.pollMultiple)
                assertTrue(vm.uiState.value.pollHideTotals)
            }
        } finally {
            rule.runOnIdle { vm.viewModelScope.cancel() }
        }
    }
}
