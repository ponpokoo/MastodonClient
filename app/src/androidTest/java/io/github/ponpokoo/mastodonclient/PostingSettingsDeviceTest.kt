package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.settings.*
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PostingSettingsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun displaysFetchedVisibilityAndConfirmsServerChangesWithoutLosingSelectionOnFailure() {
        val account = AccountSession("A", "https://one.example", "me", "me", "Me", "", "unused")
        val gate = CompletableDeferred<Result<Unit>>()
        val calls = mutableListOf<String>()
        var save: suspend () -> Result<Unit> = { gate.await() }
        val repository = object : ServerAccountSettingsRepository {
            override val serverSettings = MutableStateFlow(mapOf(account.sessionId to ServerAccountSettings(
                account.instanceUrl, account.accountId, defaults = PostingDefaults(visibility = "unlisted"))))
            override suspend fun savePostingVisibility(session: AccountSession, visibility: String): Result<Unit> {
                calls += visibility
                return save().onSuccess {
                    serverSettings.value = serverSettings.value.mapValues { (_, cached) -> cached.copy(defaults = cached.defaults!!.copy(visibility = visibility)) }
                }
            }
        }
        val preferences = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
        })
        val viewModels = ViewModelStore()
        lateinit var posting: PostingSettingsViewModel
        lateinit var maintenance: SettingsMaintenanceViewModel
        rule.runOnIdle {
            posting = PostingSettingsViewModel(repository, BrowsingSession().apply { activate(account) }).also { viewModels.put("posting", it) }
            maintenance = SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
                override val versionName = "test"
                override val versionCode = 1L
                override suspend fun clearImageCache() = Unit
            }).also { viewModels.put("maintenance", it) }
        }
        try {
            rule.setContent { MastodonClientTheme {
                SettingsScreen(preferences, account, listOf(account), maintenance, {}, {}, {}, posting = posting)
            } }
            rule.onNodeWithTag("settings_category_Composer").performScrollTo().performClick()
            rule.onNodeWithText("ひかえめな公開").assertIsDisplayed()
            rule.onNodeWithText("未取得時の公開範囲").assertDoesNotExist()
            rule.onNodeWithText("サーバーの初期設定を取得できた場合は、新規投稿にその設定を使います。").assertDoesNotExist()
            rule.onNodeWithText("公開範囲").performClick()
            rule.onNodeWithText("指定した相手のみ").assertDoesNotExist()
            rule.onNodeWithText("フォロワー限定").performClick()
            rule.onNodeWithText("保存中…").assertIsDisplayed()
            rule.onNodeWithText("ひかえめな公開").assertIsDisplayed()
            rule.runOnIdle { gate.complete(Result.success(Unit)) }
            rule.onNodeWithText("フォロワー限定").assertIsDisplayed()
            rule.runOnIdle { save = { Result.failure(IllegalArgumentException("権限を確認してください")) } }
            rule.onNodeWithText("公開範囲").performClick()
            rule.onNodeWithText("公開").performClick()
            rule.onNodeWithText("権限を確認してください").assertIsDisplayed()
            rule.onNodeWithText("フォロワー限定").assertIsDisplayed()
            rule.runOnIdle { save = { Result.success(Unit) } }
            rule.onNodeWithText("公開範囲").performClick()
            rule.onNodeWithText("公開").performClick()
            rule.onNodeWithText("公開").assertIsDisplayed()
            rule.onNodeWithText("権限を確認してください").assertDoesNotExist()
            rule.runOnIdle { assertEquals(listOf("private", "public", "public"), calls) }
        } finally { rule.runOnIdle { viewModels.clear() } }
    }
}
