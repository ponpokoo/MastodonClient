package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelinePage
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelFactory
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.navigation.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PushReauthorizationNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val account = AccountSession("one", "https://instance.example", "opaque-id", "test", "Test", "", "old")
    private val accounts = MutableStateFlow(listOf(account))
    private val control = object : PushControlRepository {
        override val states = MutableStateFlow(mapOf("one" to PushControlState(true, PushControlStatus.NEEDS_AUTH)))
        var active = false
        override suspend fun refresh() {
            states.value = mapOf("one" to PushControlState(true, if (active) PushControlStatus.ACTIVE else PushControlStatus.NEEDS_AUTH))
        }
        override suspend fun setEnabled(sessionId: String, enabled: Boolean) = Unit
        override suspend fun tokenChanged(token: String?) = Unit
    }
    private var fail = false
    private val auth = object : AuthRepository {
        override fun observeSessions() = accounts
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String): Result<AccountSession> {
            if (fail) return Result.failure(IllegalArgumentException("denied"))
            val updated = account.copy(accessToken = "new", scopes = "read write push")
            accounts.value = listOf(updated)
            control.active = true
            return Result.success(updated)
        }
        override suspend fun restoreSession() = accounts.value.firstOrNull()
        override suspend fun logout() { accounts.value = emptyList() }
    }
    private val model = PushSettingsViewModel(control, auth)
    private lateinit var nav: NavHostController
    private val createdSessions = mutableListOf<MainSessionViewModel>()

    @After fun cleanup() { rule.runOnIdle { model.viewModelScope.cancel() } }

    private fun show(restoration: StateRestorationTester? = null) {
        val store = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        val maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
            override val versionName = "test"
            override val versionCode = 1L
            override suspend fun clearImageCache() = Unit
        })
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(emptyList(), null, true))
        }
        val factory = ScreenViewModelFactory {
            MainSessionViewModel(auth, repository).also { createdSessions += it }
        }
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                DisposableEffect(Unit) {
                    // Discard all navigation entry models when saving/restoring, as on process death.
                    onDispose { owner.viewModelStore.clear() }
                }
                MastodonClientTheme {
                    nav = rememberNavController()
                    AccountRemovalNavigation(model, nav, ready = true)
                    val sessions by accounts.collectAsStateWithLifecycle()
                    ScreenNavHost(nav, Route.Timeline) {
                        animatedScreen<Route.Timeline> { entry ->
                            mainSessionViewModel(entry, factory)
                            Text("Home")
                        }
                        animatedScreen<Route.Login> { Text("Login") }
                        animatedScreen<Route.Settings> { entry ->
                            val mainEntry = remember(entry) { nav.getBackStackEntry<Route.Timeline>() }
                            mainSessionViewModel(mainEntry, factory)
                            SettingsScreen(store, sessions.firstOrNull(), sessions, maintenance,
                                onBack = { nav.popBackStack() }, onLogout = model::logout, onAddAccount = {},
                                pushSettings = model, initialPage = "Notifications")
                        }
                    }
                }
            }
        }
        if (restoration == null) rule.setContent(content) else restoration.setContent(content)
        rule.runOnIdle { nav.navigate(Route.Settings("Notifications")) }
        rule.onNodeWithText("通知を受け取る方法").assertIsDisplayed()
    }

    @Test fun reauthorizationPreservesNotificationPageScrollAndBackHistory() {
        show()
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasText("アプリ表示中のAndroid通知"))
        val before = rule.onNodeWithTag("settings_screen").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        rule.runOnIdle { model.complete("callback") }
        rule.onNodeWithText("アプリ表示中のAndroid通知").assertIsDisplayed()
        val after = rule.onNodeWithTag("settings_screen").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.01f)
        rule.runOnIdle { assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<Route.Settings>()) }
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("push_settings"))
        rule.onNodeWithText("有効（端末の通知許可に従います）").assertIsDisplayed()
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithTag("settings_category_Notifications").assertExists()
    }

    @Test fun deniedReauthorizationStaysInSettingsAndLastAccountRemovalStillOpensLogin() {
        show()
        fail = true
        rule.runOnIdle { model.complete("denied") }
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("push_settings"))
        rule.onNodeWithText("再認証を完了できませんでした。元のアカウントで再試行してください。").assertIsDisplayed()
        rule.runOnIdle {
            assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<Route.Settings>())
            model.logout(account)
        }
        rule.onNodeWithText("Login").assertIsDisplayed()
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test fun restoredNotificationSettingsCreatesSessionWithoutComposingHomeFirstAndCompletesReauthorization() {
        val restoration = StateRestorationTester(rule)
        show(restoration)
        rule.runOnIdle { assertEquals(1, createdSessions.size) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("通知を受け取る方法").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(2, createdSessions.size)
            assertNotSame(createdSessions.first(), createdSessions.last())
            assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<Route.Settings>())
            model.complete("callback")
        }
        rule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("push_settings"))
        rule.onNodeWithText("有効（端末の通知許可に従います）").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("new", createdSessions.last().uiState.value.session?.accessToken)
        }
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithTag("settings_category_Notifications").assertExists()
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, createdSessions.size) }
    }
}
