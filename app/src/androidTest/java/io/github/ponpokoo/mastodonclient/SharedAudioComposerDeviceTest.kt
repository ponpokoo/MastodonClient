package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostScreen
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import io.github.ponpokoo.mastodonclient.feature.compose.IncomingShare
import io.github.ponpokoo.mastodonclient.navigation.Route
import io.github.ponpokoo.mastodonclient.navigation.openSharedComposer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SharedAudioComposerDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun newShareReplacesComposerAndPreservesMultipleUriNavigationArguments() {
        lateinit var navigation: NavHostController
        rule.setContent {
            navigation = rememberNavController()
            NavHost(navigation, startDestination = Route.Timeline) {
                composable<Route.Timeline> { Text("home") }
                composable<Route.ComposePost> { Text("composer") }
            }
        }
        rule.runOnIdle { navigation.openSharedComposer(IncomingShare("one", mediaUris = listOf("content://files/one"))) }
        rule.waitForIdle()
        val firstId = navigation.currentBackStackEntry!!.id
        val uris = listOf("content://files/two", "content://files/three")
        rule.runOnIdle { navigation.openSharedComposer(IncomingShare("two", text = "caption", mediaUris = uris)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertNotEquals(firstId, navigation.currentBackStackEntry!!.id)
            val route = navigation.currentBackStackEntry!!.toRoute<Route.ComposePost>()
            assertEquals(uris, route.sharedMediaUris)
            assertEquals("caption", route.sharedText)
            assertEquals("two", route.shareRequestId)
            navigation.popBackStack()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(navigation.currentDestination!!.hasRoute<Route.Timeline>()) }
    }

    @Test fun sharedAudioRendersAsAudioAndReportsTheRejectedFileWithoutUploadingIt() {
        val session = AccountSession("test", "https://example.test", "me", "me", "Me", "", "test-token")
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val uploads = mutableListOf<MediaUpload>()
        lateinit var model: ComposePostViewModel
        rule.runOnIdle {
            model = ComposePostViewModel(null, null, object : TimelineRepository {
                override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                    Result.success(TimelinePage(emptyList(), null, true))
                override suspend fun getComposerConfiguration(session: AccountSession) =
                    Result.success(ComposerConfiguration(supportedMimeTypes = setOf("audio/mpeg")))
                override suspend fun uploadMedia(session: AccountSession, upload: MediaUpload): Result<UploadedMedia> {
                    uploads += upload
                    return Result.success(UploadedMedia("audio-id", "audio", null, ""))
                }
            }, object : AuthRepository {
                override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
                override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
                override suspend fun restoreSession() = session
                override suspend fun getSessions() = listOf(session)
                override suspend fun logout() = Unit
            }, UserPreferencesStore(preferences), {}, object : DraftMediaRepository {
                override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult(listOf(
                    DraftAttachment("file:///synthetic-song", "song.mp3", "audio/mpeg"),
                    DraftAttachment("file:///synthetic-pdf", "document.pdf", "application/pdf"),
                )))
            }, initialSharedMediaUris = listOf("content://test/song", "content://test/pdf"))
        }
        try {
            rule.setContent { MaterialTheme { ComposePostScreen(model, onClose = {}, onPosted = {}) } }
            rule.waitUntil(5_000) { model.uiState.value.attachments.singleOrNull()?.transferState == MediaTransferState.Ready }
            rule.onNodeWithContentDescription("音声ファイル").assertExists()
            rule.onNodeWithText("song.mp3").assertExists()
            rule.onNodeWithText("2件中1件", substring = true).assertExists()
            rule.onNodeWithText("document.pdf", substring = true).assertExists()
            rule.runOnIdle { assertEquals(listOf("audio/mpeg"), uploads.map { it.mimeType }) }
        } finally {
            rule.runOnIdle { model.viewModelScope.cancel() }
        }
    }
}
