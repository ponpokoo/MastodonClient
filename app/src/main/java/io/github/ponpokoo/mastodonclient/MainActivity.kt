package io.github.ponpokoo.mastodonclient

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.navigation.AppNavigation
import io.github.ponpokoo.mastodonclient.feature.login.OAuthCallbackBus
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        OAuthCallbackBus.accept(intent?.data)
        intent?.data = null
        setContent {
            val preferencesStore = remember { UserPreferencesStore(applicationContext) }
            val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(
                initialValue = AppPreferences(),
            )
            MastodonClientTheme(themeMode = preferences.themeMode) {
                AppNavigation(preferences = preferencesStore)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OAuthCallbackBus.accept(intent.data)
        intent.data = null
    }
}
