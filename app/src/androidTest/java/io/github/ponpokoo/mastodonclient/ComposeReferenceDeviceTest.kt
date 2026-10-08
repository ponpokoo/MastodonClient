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
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComposeReferenceDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val session = AccountSession("test", "https://example.test", "me", "me", "Me", "", "test-token")
    private val target = TimelineStatus(
        timelineId = "target", statusId = "target", createdAt = "2026-10-01T00:00:00Z",
        author = StatusAuthor("alice", "Alice", "alice@example.test", ""), boostedBy = null,
        contentHtml = "<p>Target body</p>", spoilerText = "", sensitive = false, visibility = "public",
        url = "https://example.test/@alice/target", repliesCount = 0, boostsCount = 0,
        favouritesCount = 0, mediaAttachments = emptyList(), quoteApproval = "automatic",
    )
    private lateinit var model: ComposePostViewModel

    private fun show(reply: Boolean = false, quoteMode: QuoteMode? = null,
        pending: CompletableDeferred<Result<TimelineStatus>>? = null) {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        rule.runOnIdle {
            model = ComposePostViewModel(target.statusId.takeIf { reply }, null, object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override fun getCachedStatus(session: AccountSession, statusId: String) = target.takeIf { pending == null }
                override suspend fun getTimelineStatus(session: AccountSession, timelineId: String) =
                    withContext(NonCancellable) { pending?.await() ?: Result.success(target) }
            }, object : AuthRepository {
                override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
                override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
                override suspend fun restoreSession() = session
                override suspend fun getSessions() = listOf(session)
                override suspend fun logout() = Unit
            }, UserPreferencesStore(preferences), {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult())
            }, initialQuoteStatusId = target.statusId.takeIf { quoteMode != null },
                initialQuoteStatusUrl = target.url.takeIf { quoteMode != null }, nativeQuote = quoteMode == QuoteMode.Native)
        }
        rule.setContent { MaterialTheme { ComposePostScreen(model, onClose = {}, onPosted = {}) } }
        rule.waitUntil(5_000) { !model.uiState.value.isLoading }
    }

    @Test fun replyCloseButtonKeepsMentionAndBody() {
        try {
            show(reply = true)
            rule.onNodeWithTag("compose_text").performTextInput("comment")
            val text = model.uiState.value.text
            rule.onNodeWithContentDescription("返信を解除").performClick()
            rule.onNodeWithText("Target body").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals(text, model.uiState.value.text)
                assertTrue(text.contains("@alice@example.test"))
                assertNull(model.uiState.value.replyToId)
            }
        } finally {
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun nativeQuoteCloseButtonWorksBeforePreviewArrivesAndEnablesPoll() {
        val pending = CompletableDeferred<Result<TimelineStatus>>()
        try {
            show(quoteMode = QuoteMode.Native, pending = pending)
            rule.onNodeWithTag("compose_text").performTextInput("comment")
            rule.onNodeWithContentDescription("引用を解除").performClick()
            rule.runOnIdle {
                assertNull(model.uiState.value.quoteStatusId)
                assertFalse(model.uiState.value.quotingNative)
                pending.complete(Result.success(target))
            }
            rule.waitForIdle()
            rule.onNodeWithText("Target body").assertDoesNotExist()
            rule.onNodeWithContentDescription("引用を解除").assertDoesNotExist()
            rule.onNodeWithContentDescription("投票を追加").performScrollTo().performClick()
            rule.runOnIdle {
                assertEquals("comment", model.uiState.value.text)
                assertEquals(2, model.uiState.value.pollOptions.size)
            }
        } finally {
            pending.complete(Result.success(target))
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun removingLinkQuoteUrlHidesPreviewAndKeepsOtherText() {
        try {
            show(quoteMode = QuoteMode.Link)
            rule.onNodeWithText("Target body").assertExists()
            rule.onNodeWithTag("compose_text").performTextReplacement("comment https://other.example/post")
            rule.onNodeWithContentDescription("引用を解除").assertDoesNotExist()
            rule.onNodeWithText("Target body").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals("comment https://other.example/post", model.uiState.value.text)
                assertNull(model.uiState.value.quoteStatusId)
            }
        } finally {
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }

}
