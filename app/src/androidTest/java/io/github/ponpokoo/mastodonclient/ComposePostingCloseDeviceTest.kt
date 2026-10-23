package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Delayed local responses exercise closing without sending any real posts. */
class ComposePostingCloseDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("posting-test", "https://example.test", "me", "me", "Me", "", "fixture")
    private val status = TimelineStatus("post", "post", "2026-10-23T00:00:00Z",
        StatusAuthor("me", "Me", "me@example.test", ""), null,
        "Fixture post", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val response = CompletableDeferred<Result<TimelineStatus>>()
    private val requests = AtomicInteger()
    private var manualCloses = 0
    private var successfulCloses = 0
    private lateinit var model: ComposePostViewModel

    private fun show(editing: Boolean = false) {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        rule.runOnIdle {
            model = ComposePostViewModel(null, status.statusId.takeIf { editing }, object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override suspend fun getEditableStatus(session: AccountSession, statusId: String) =
                    Result.success(EditableStatus(statusId, "before edit", "", false, null))
                override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                    check(!editing)
                    requests.incrementAndGet()
                    return response.await()
                }
                override suspend fun updateStatus(session: AccountSession, status: EditableStatus): Result<TimelineStatus> {
                    check(editing)
                    requests.incrementAndGet()
                    return response.await()
                }
            }, object : AuthRepository {
                override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
                override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
                override suspend fun restoreSession() = session
                override suspend fun getSessions() = listOf(session)
                override suspend fun logout() = Unit
            }, UserPreferencesStore(preferences), {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
            })
        }
        val shown = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                if (shown.value) ComposePostScreen(model, isEditing = editing,
                    onClose = { manualCloses++; shown.value = false },
                    onPosted = { successfulCloses++; shown.value = false })
                else Text("Composer closed")
            }
        }
        rule.waitUntil(5_000) { !model.uiState.value.isLoading }
        rule.onNodeWithTag("compose_text").performTextReplacement("keep this input")
    }

    private fun startSending() {
        rule.onNodeWithTag("compose_submit").performClick()
        rule.waitUntil(5_000) { requests.get() == 1 }
        rule.onNodeWithContentDescription("閉じる").assertIsNotEnabled()
    }

    private fun attemptCloseWhileSending() {
        val handle = rule.onNodeWithTag("compose_dismiss_handle")
        val before = handle.fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithContentDescription("閉じる").performClick()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        handle.performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 600f), 300)
        }
        rule.onNodeWithTag("compose_text").assertIsDisplayed()
        assertEquals(before, handle.fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.runOnIdle {
            assertEquals(0, manualCloses)
            assertEquals(0, successfulCloses)
            assertTrue(model.uiState.value.isPosting)
        }
    }

    @Test fun newPostIgnoresCloseBackAndSwipeThenClosesOnlyOnSuccess() {
        try {
            show()
            startSending()
            attemptCloseWhileSending()
            rule.runOnIdle { response.complete(Result.success(status)) }
            rule.onNodeWithText("Composer closed").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals(0, manualCloses)
                assertEquals(1, successfulCloses)
                assertEquals(1, requests.get())
            }
        } finally {
            if (::model.isInitialized) rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun editFailureKeepsInputAndErrorThenAllowsManualClose() {
        try {
            show(editing = true)
            startSending()
            attemptCloseWhileSending()
            rule.runOnIdle { response.complete(Result.failure(IllegalStateException("Simulated send failure"))) }
            rule.waitUntil(5_000) { !model.uiState.value.isPosting }
            rule.onNodeWithTag("compose_text").assertTextContains("keep this input")
            rule.onNodeWithText("Simulated send failure").performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription("閉じる").assertIsEnabled().performClick()
            rule.onNodeWithText("Composer closed").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals(1, manualCloses)
                assertEquals(0, successfulCloses)
            }
        } finally {
            if (::model.isInitialized) rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test fun sendingDuringDismissDragRestoresComposerAndStillWaitsForResult() {
        try {
            show()
            val handle = rule.onNodeWithTag("compose_dismiss_handle")
            val before = handle.fetchSemanticsNode().boundsInRoot.top
            handle.performTouchInput {
                down(center)
                moveBy(Offset(0f, 600f))
            }
            rule.runOnIdle { model.post() }
            rule.waitUntil(5_000) { requests.get() == 1 }
            handle.performTouchInput { up() }
            rule.waitForIdle()
            assertEquals(before, handle.fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.runOnIdle {
                assertEquals(0, manualCloses)
            }
            rule.runOnIdle { response.complete(Result.success(status)) }
            rule.onNodeWithText("Composer closed").assertIsDisplayed()
            rule.runOnIdle { assertEquals(1, successfulCloses) }
        } finally {
            if (::model.isInitialized) rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }
}
