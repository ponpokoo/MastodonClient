package io.github.ponpokoo.mastodonclient.feature.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NotificationsLifecycleEffect(viewModel: NotificationsViewModel, isVisible: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, isVisible) {
        if (isVisible) viewModel.onNotificationsVisible()
    }
    DisposableEffect(viewModel, isVisible) {
        onDispose {
            if (isVisible) viewModel.onNotificationsHidden()
        }
    }
    // Keep one observer across tab switches: adding an observer to a started owner
    // replays ON_START and would otherwise refresh every time the tab is selected.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onAppForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
