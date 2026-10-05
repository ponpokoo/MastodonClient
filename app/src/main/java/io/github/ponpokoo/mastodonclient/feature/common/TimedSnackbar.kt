package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Shared by draft saving and moderation; start the two seconds when this message is visible. */
suspend fun SnackbarHostState.showTwoSecondSnackbar(message: String, actionLabel: String? = null): SnackbarResult = coroutineScope {
    currentSnackbarData?.dismiss()
    val dismissJob = launch {
        val data = snapshotFlow { currentSnackbarData }.first { it?.visuals?.message == message }
        delay(2_000)
        data?.dismiss()
    }
    try { showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite) }
    finally { dismissJob.cancel() }
}
