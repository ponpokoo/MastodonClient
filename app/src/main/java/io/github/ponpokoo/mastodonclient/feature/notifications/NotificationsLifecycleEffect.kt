package io.github.ponpokoo.mastodonclient.feature.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NotificationsLifecycleEffect(viewModel: NotificationsViewModel, isVisible: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val visible by rememberUpdatedState(isVisible)
    val observation = remember(lifecycleOwner, viewModel) { NotificationVisibility() }

    DisposableEffect(lifecycleOwner, viewModel) {
        var stopped = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    observation.resumed = true
                    val becameVisible = visible && !observation.presented
                    if (becameVisible) observation.presented = true
                    if (stopped) {
                        stopped = false
                        viewModel.onLifecycleStarted(becameVisible)
                    } else if (becameVisible) {
                        viewModel.onNotificationsVisible()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> observation.resumed = false
                Lifecycle.Event.ON_STOP -> stopped = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (observation.presented) viewModel.onNotificationsHidden()
        }
    }

    SideEffect {
        if (!isVisible && observation.presented) {
            observation.presented = false
            viewModel.onNotificationsHidden()
        } else if (isVisible && !observation.presented && observation.resumed) {
            observation.presented = true
            viewModel.onNotificationsVisible()
        }
    }
}

private class NotificationVisibility {
    var presented = false
    // A newly attached observer must process its first resume before a tab-change refresh.
    var resumed = false
}
