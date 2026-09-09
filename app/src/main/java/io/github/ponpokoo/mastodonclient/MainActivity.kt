package io.github.ponpokoo.mastodonclient

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.navigation.AppNavigation
import io.github.ponpokoo.mastodonclient.feature.login.OAuthCallbackBus
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components {
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                else add(GifDecoder.Factory())
            }.build()
        }
        OAuthCallbackBus.accept(intent?.data)
        intent?.data = null
        setContent {
            val preferencesStore = remember { UserPreferencesStore(applicationContext) }
            val preferences by preferencesStore.preferences.collectAsStateWithLifecycle(
                initialValue = AppPreferences(),
            )
            val darkTheme = when (preferences.themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> isSystemInDarkTheme()
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
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
