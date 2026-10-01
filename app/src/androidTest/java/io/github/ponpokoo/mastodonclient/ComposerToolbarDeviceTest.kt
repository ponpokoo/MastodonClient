package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ComposerToolbarDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun languageSelectionAndHashInsertionWorkInComposer() {
        val session = AccountSession("test", "https://example.test", "me", "me", "Me", "", "test-token")
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
                override suspend fun importMedia(uris: List<String>) = Result.success(emptyList<DraftAttachment>())
            })
        }
        try {
            rule.setContent { MaterialTheme { ComposePostScreen(model, onClose = {}, onPosted = {}) } }
            rule.waitUntil(5_000) { !model.uiState.value.isLoading }
            rule.onNodeWithContentDescription("投稿の言語：デフォルト").performScrollTo().performClick()
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("日本語 (ja)"))
            rule.onNodeWithText("日本語 (ja)").performClick()
            rule.runOnIdle { assertEquals("ja", model.uiState.value.language) }
            rule.onNodeWithContentDescription("投稿の言語：ja").performScrollTo().performClick()
            rule.onNodeWithText("デフォルト").performClick()
            rule.runOnIdle { assertNull(model.uiState.value.language) }

            rule.onNodeWithTag("compose_text").performTextInput("前後")
            rule.onNodeWithTag("compose_text").performTextInputSelection(TextRange(1))
            rule.onNodeWithContentDescription("ハッシュタグを挿入").performScrollTo().performClick()
            rule.runOnIdle { assertEquals("前#後", model.uiState.value.text) }
        } finally {
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }
}
