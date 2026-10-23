package io.github.ponpokoo.mastodonclient.feature.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NotificationsLifecycleEffect(viewModel: NotificationsViewModel, isVisible: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, isVisible) {
        var wasVisible = false
        val observer = LifecycleEventObserver { _, event ->
            // A predictive preview reaches STARTED without becoming the visible destination.
            if (event == Lifecycle.Event.ON_RESUME && isVisible && !wasVisible) {
                wasVisible = true
                viewModel.onNotificationsVisible()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (wasVisible) viewModel.onNotificationsHidden()
        }
    }
    // Refresh on a committed foreground return, not on preview or its cancellation.
    // Keep this observer across tab switches to avoid replaying a foreground refresh.
    DisposableEffect(lifecycleOwner, viewModel) {
        var stopped = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (stopped) {
                    stopped = false
                    viewModel.onLifecycleStarted()
                }
                Lifecycle.Event.ON_STOP -> stopped = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
