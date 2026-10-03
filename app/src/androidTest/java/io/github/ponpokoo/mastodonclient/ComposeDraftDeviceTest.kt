package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeDraftDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun saveNotificationOpensDraftsAndDraftCanBeRestoredAndDeleted() {
        val session = AccountSession("draft-test", "https://social.example", "me", "ponta", "Ponta", "", "test-token")
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        lateinit var model: ComposePostViewModel
        rule.runOnIdle {
            model = ComposePostViewModel(null, null, object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
            }, object : AuthRepository {
                override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
                override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
                override suspend fun restoreSession() = session
                override suspend fun getSessions() = listOf(session)
                override suspend fun logout() = Unit
            }, UserPreferencesStore(preferences), {}, object : DraftMediaRepository {
                override suspend fun importMedia(uris: List<String>) = Result.success(MediaImportResult())
            })
        }
        try {
            rule.setContent { MaterialTheme { ComposePostScreen(model, onClose = {}, onPosted = {}) } }
            rule.waitUntil(5_000) { !model.uiState.value.isLoading }
            rule.onNodeWithTag("compose_text").performTextInput("保存して再編集する本文")
            rule.onNodeWithContentDescription("下書きに保存").performScrollTo().performClick()
            rule.waitUntil(5_000) { model.uiState.value.drafts.size == 1 && !model.uiState.value.isSavingDraft }
            rule.runOnIdle { assertEquals("", model.uiState.value.text) }
            rule.onNodeWithText("下書きを見る").performClick()
            rule.onNodeWithText("@ponta@social.example", useUnmergedTree = true).assertIsDisplayed()
            rule.onNodeWithText("添付なし", useUnmergedTree = true).assertDoesNotExist()
            rule.onNodeWithText("保存して再編集する本文").performClick()
            rule.waitUntil(5_000) { !model.uiState.value.isLoading }
            rule.runOnIdle { assertEquals("保存して再編集する本文", model.uiState.value.text) }
            rule.onNodeWithContentDescription("下書きに保存").performScrollTo().performClick()
            rule.waitUntil(5_000) { model.uiState.value.drafts.size == 1 && !model.uiState.value.isSavingDraft }
            rule.onNodeWithTag("compose_drafts").performClick()
            rule.onNodeWithContentDescription("下書きのメニュー").performClick()
            rule.onNodeWithText("削除").performClick()
            rule.onNodeWithText("削除", useUnmergedTree = true).performClick()
            rule.waitUntil(5_000) { model.uiState.value.drafts.isEmpty() }
            rule.onNodeWithText("保存された下書きはありません").assertIsDisplayed()
        } finally {
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }
}
