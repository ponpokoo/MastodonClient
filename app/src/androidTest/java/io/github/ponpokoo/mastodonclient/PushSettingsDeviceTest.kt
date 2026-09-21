package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PushSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountSession("one", "https://instance.example", "id", "user", "User", "", "synthetic")
    private class Control(status: PushControlStatus) : PushControlRepository {
        override val states = MutableStateFlow(mapOf("one" to PushControlState(status = status)))
        var changes = 0
        override suspend fun refresh() { }
        override suspend fun tokenChanged(token: String?) { }
        override suspend fun setEnabled(sessionId: String, enabled: Boolean) {
            changes++
            states.value = mapOf(sessionId to PushControlState(enabled, if (enabled) PushControlStatus.NEEDS_AUTH else PushControlStatus.OFF))
        }
    }
    private inner class Auth : AuthRepository {
        var requested: String? = null
        override suspend fun createAuthorizationUrl(instanceUrl: String): Result<String> = error("Not used")
        override suspend fun createPushAuthorizationUrl(sessionId: String): Result<String> {
            requested = sessionId
            return Result.success("https://instance.example/oauth/authorize")
        }
        override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> = error("Not used")
        override suspend fun restoreSession() = account
        override suspend fun logout() { }
    }
    @Test fun unconfiguredServicePreventsOptIn() {
        val control = Control(PushControlStatus.PREPARING)
        val model = PushSettingsViewModel(control, Auth())
        compose.setContent { MaterialTheme { PushNotificationSettings(model, listOf(account)) } }
        compose.onNodeWithTag("push_switch_one").assertIsNotEnabled().assertIsOff()
        assertEquals(0, control.changes)
        compose.onNodeWithText("再認証する").assertDoesNotExist()
    }
    @Test fun accountOptInOffersReauthorizationAndCanBeDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val control = Control(PushControlStatus.OFF); val auth = Auth()
        val model = PushSettingsViewModel(control, auth)
        compose.setContent { MaterialTheme { PushNotificationSettings(model, listOf(account)) } }
        compose.onNodeWithTag("push_switch_one").performClick()
        compose.onNodeWithText("再認証する").performClick()
        compose.runOnIdle { assertEquals(account.sessionId, auth.requested); assertNotNull(model.authorizationUrl.value) }
        compose.onNodeWithTag("push_switch_one").assertIsOn().performClick()
        compose.onNodeWithTag("push_switch_one").assertIsOff()
        compose.onNodeWithText("再認証する").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, control.changes) }
    }
}
