package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
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
import io.github.ponpokoo.mastodonclient.notification.NotificationPollingScheduler
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
            val notificationEnabled by produceState<Boolean?>(null, preferencesStore) {
                preferencesStore.preferences.collect { current ->
                    value = current.simpleNotificationsEnabled
                }
            }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) NotificationPollingScheduler.runImmediately(applicationContext)
            }
            LaunchedEffect(notificationEnabled) {
                val enabled = notificationEnabled ?: return@LaunchedEffect
                NotificationPollingScheduler.ensureNotificationChannel(applicationContext)
                val registered = NotificationPollingScheduler.setEnabled(
                    applicationContext,
                    enabled,
                )
                preferencesStore.setSimpleNotificationSetupFailed(!registered)
                if (
                    enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (enabled) {
                    NotificationPollingScheduler.runImmediately(applicationContext)
                }
            }
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
