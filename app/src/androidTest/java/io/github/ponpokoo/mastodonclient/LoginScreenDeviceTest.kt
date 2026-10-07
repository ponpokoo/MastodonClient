package io.github.ponpokoo.mastodonclient

import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MastodonInstance
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.InstanceRepository
import io.github.ponpokoo.mastodonclient.feature.login.InstanceLoginContent
import io.github.ponpokoo.mastodonclient.feature.login.LoginViewModel
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.io.File

class LoginScreenDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: LoginViewModel
    private var connections = 0
    private var backCalls = 0
    private val auth = object : AuthRepository {
        var prepares = 0
        var completedSession: AccountSession? = null
        override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> {
            prepares++
            return Result.failure(IOException("synthetic preparation failure"))
        }
        override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> =
            Result.success(requireNotNull(completedSession))
        override suspend fun restoreSession(): AccountSession? = null
        override suspend fun logout() { }
    }
    private fun show(failFirstConnection: Boolean = false, addingAccount: Boolean = true) {
        compose.runOnUiThread {
            model = LoginViewModel(object : InstanceRepository {
                override suspend fun discover(input: String): Result<MastodonInstance> {
                    connections++
                    return if (failFirstConnection && connections == 1) Result.failure(IOException("synthetic"))
                    else Result.success(MastodonInstance(input, "https://$input", "Test server", null, 500, 4))
                }
            }, auth, restoreExistingSession = false)
        }
        compose.setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            MastodonClientTheme {
                InstanceLoginContent(state, model::onInstanceChanged, model::discover,
                    model::startAuthorization, onBack = if (addingAccount) ({ backCalls++ }) else null)
            }
        }
    }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnUiThread { model.viewModelScope.cancel() } }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, name).outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test fun confirmedServerKeepsEditableFieldAndChangingItRequiresCheckingAgain() {
        show()
        compose.onNodeWithTag("instance_input").performTextInput("one.example")
        compose.onNodeWithTag("instance_input").performImeAction()
        compose.onNodeWithTag("instance_success").assertIsDisplayed()
        compose.onNodeWithTag("instance_input").assert(hasText("one.example"))
        compose.onNodeWithTag("discover_button").assertDoesNotExist()
        compose.onNodeWithTag("authorize_button").assertIsDisplayed()
        compose.onNodeWithTag("confirmed_instance_url").assert(hasText("https://one.example"))
        capture("login-confirmed.png")
        compose.onNodeWithTag("instance_input").performTextReplacement("two.example")
        compose.onNodeWithTag("authorize_button").assertDoesNotExist()
        compose.onNodeWithTag("instance_success").assertDoesNotExist()
        compose.onNodeWithTag("discover_button").performClick()
        compose.onNodeWithTag("confirmed_instance_url").assert(hasText("https://two.example"))
        compose.runOnIdle { assertEquals(2, connections) }
        compose.onNodeWithTag("change_instance").performClick()
        compose.onNodeWithTag("instance_input").assertIsFocused()
        compose.onNodeWithTag("instance_input").assert(hasText("two.example"))
        compose.onNodeWithTag("authorize_button").assertDoesNotExist()
    }

    @Test fun connectionAndAuthorizationFailuresAllowRetryInTheSameScreen() {
        show(failFirstConnection = true)
        compose.onNodeWithTag("instance_input").performTextInput("one.example")
        compose.onNodeWithTag("discover_button").performClick()
        compose.onNodeWithTag("login_error").assertIsDisplayed()
        compose.onNodeWithTag("discover_button").assertIsEnabled().performClick()
        compose.onNodeWithTag("login_error").assertDoesNotExist()
        compose.onNodeWithTag("authorize_button").performClick()
        compose.onNodeWithTag("login_error").assertIsDisplayed()
        compose.onNodeWithTag("instance_success").assertIsDisplayed()
        compose.onNodeWithTag("authorize_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, auth.prepares); assertEquals(2, connections) }
        compose.onNodeWithTag("login_back").performClick()
        compose.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test fun invalidInputShowsFieldErrorWithoutConnectingAndCorrectionClearsIt() {
        show()
        compose.onNodeWithTag("instance_input").performTextInput("https://one.example/path")
        compose.onNodeWithTag("discover_button").performClick()
        compose.onNodeWithTag("instance_input").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("login_error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, connections) }
        compose.onNodeWithTag("instance_input").performTextReplacement("one.example")
        compose.onNodeWithTag("discover_button").performClick()
        compose.onNodeWithTag("instance_success").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test fun successfulAuthenticationShowsProgressWithoutAccountRemovalOrLoginActions() {
        show(addingAccount = false)
        compose.onNodeWithTag("instance_input").assertIsDisplayed()
        val session = AccountSession(
            sessionId = "login-test", instanceUrl = "https://one.example/", accountId = "account-test",
            username = "alice", displayName = "Alice", avatarUrl = "", accessToken = "synthetic-token",
        )
        compose.runOnIdle {
            auth.completedSession = session
            model.completeAuthorization("synthetic-callback")
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithTag("remove_account").assertDoesNotExist()
        compose.onNodeWithTag("instance_input").assertDoesNotExist()
        compose.onNodeWithText(session.displayName).assertDoesNotExist()
        compose.onNodeWithText("@${session.username}").assertDoesNotExist()
        compose.onNodeWithText(session.instanceUrl).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(session, model.uiState.value.session) }
    }
}
