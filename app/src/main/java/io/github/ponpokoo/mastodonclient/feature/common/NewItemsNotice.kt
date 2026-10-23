package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class NewItemsNotice(
    val id: Long,
    val message: String,
    val actionable: Boolean,
    val dismissAfterMillis: Long? = null,
)

data class NewItemsNoticeResult(val id: Long, val count: Int)

/** Presentation only: acknowledging rows and moving the viewport remain with their owners. */
internal class NewItemsNoticeState(private val clock: () -> Long = System::currentTimeMillis) {
    var notice by mutableStateOf<NewItemsNotice?>(null)
        private set
    private var sequence = 0L
    private var lastResultKey: Long? = null
    private var restoring = false
    private var observedNotificationIds: Set<String> = emptySet()
    var notificationExpiresAtMillis by mutableStateOf<Long?>(null)
        private set

    /** Notification receipts are timed results, independent of viewport and read acknowledgements. */
    fun updateNotificationCount(pendingIds: Set<String>) {
        val hasArrivals = (pendingIds - observedNotificationIds).isNotEmpty()
        observedNotificationIds = pendingIds
        if (hasArrivals) {
            notice = NewItemsNotice(++sequence, "新着${pendingIds.size}件", false, 1_800)
            // Once shown, additional arrivals renew the running timer even while the tab is hidden.
            if (notificationExpiresAtMillis != null) notificationExpiresAtMillis = clock() + 1_800
        }
        else if (notice?.actionable == true) notice = null
    }

    fun startNotificationTimer(id: Long) {
        val duration = notice?.takeIf { it.id == id }?.dismissAfterMillis ?: return
        if (notificationExpiresAtMillis == null) notificationExpiresAtMillis = clock() + duration
    }

    fun remainingNotificationMillis(id: Long): Long? {
        if (notice?.id != id) return null
        return notificationExpiresAtMillis?.let { (it - clock()).coerceAtLeast(0) }
    }

    fun update(pendingCount: Int, atTop: Boolean, resultKey: Long? = null,
        resultCount: Int? = null, isRestored: Boolean = false) {
        val newResult = resultKey != null && resultKey != lastResultKey
        if (resultKey != null) lastResultKey = resultKey
        if (isRestored) {
            if (!restoring) notice = NewItemsNotice(++sequence, "↑ 前回の位置を表示中", true)
            restoring = true
            return
        }
        val current = notice
        // Freeze both wording and count throughout a pending notice's lifetime.
        if (current?.actionable == true) {
            if (atTop) {
                notice = current.copy(id = ++sequence, actionable = false, dismissAfterMillis = 600)
                restoring = false
            } else if (pendingCount == 0 && !restoring) notice = null
            return
        }
        if (pendingCount > 0 && !atTop) {
            notice = NewItemsNotice(++sequence, "↑ 新着${pendingCount}件", true)
        } else if (current?.dismissAfterMillis == 600L) {
            // An arrival must not turn into a fresh at-top result while it fades away.
            return
        } else if (newResult && resultCount != null && resultCount > 0) {
            notice = NewItemsNotice(++sequence, "新着${resultCount}件", false, 1_800)
        } else if (pendingCount > 0 && atTop && current == null) {
            notice = NewItemsNotice(++sequence, "新着${pendingCount}件", false, 1_800)
        }
    }

    fun dismiss(id: Long) {
        if (notice?.id == id) {
            notice = null
            notificationExpiresAtMillis = null
        }
    }

    companion object {
        // Preserve the frozen label when a detail screen recreates the main composition.
        val Saver = saver(System::currentTimeMillis)

        fun saver(clock: () -> Long): Saver<NewItemsNoticeState, Any> = listSaver<NewItemsNoticeState, Any?>(
            save = { listOf(it.sequence, it.lastResultKey, it.restoring, it.notice?.id,
                it.notice?.message, it.notice?.actionable, it.notice?.dismissAfterMillis,
                it.observedNotificationIds.toList(), it.notificationExpiresAtMillis) },
            restore = { values -> NewItemsNoticeState(clock).apply {
                sequence = values[0] as Long
                lastResultKey = values[1] as Long?
                restoring = values[2] as Boolean
                observedNotificationIds = (values.getOrNull(7) as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
                notice = (values[3] as Long?)?.let { id -> NewItemsNotice(id,
                    values[4] as String, values[5] as Boolean, values[6] as Long?) }
                notificationExpiresAtMillis = values.getOrNull(8) as? Long
                if (notificationExpiresAtMillis?.let { it <= clock() } == true) {
                    notice?.let { dismiss(it.id) }
                }
            } },
        )
    }
}

/** Start on first display, then keep the deadline across tab changes and saved-state restoration. */
@Composable
internal fun NotificationNoticeEffect(state: NewItemsNoticeState, visible: Boolean) {
    val notice = state.notice
    LaunchedEffect(state, notice?.id, visible) {
        if (visible && notice != null) state.startNotificationTimer(notice.id)
    }
    val expiresAt = state.notificationExpiresAtMillis
    LaunchedEffect(state, notice?.id, expiresAt) {
        if (notice != null) {
            val remaining = state.remainingNotificationMillis(notice.id) ?: return@LaunchedEffect
            delay(remaining)
            state.dismiss(notice.id)
        }
    }
}

@Composable
internal fun NewItemsNoticeEffect(state: NewItemsNoticeState, visible: Boolean) {
    val notice = state.notice
    LaunchedEffect(notice?.id, visible) {
        if (visible && notice?.dismissAfterMillis != null) {
            delay(notice.dismissAfterMillis)
            state.dismiss(notice.id)
        }
    }
}

/** The normal notice anchor stays unchanged; simultaneous messages occupy the row above it. */
@Composable
internal fun TimelineNoticeStack(
    notice: NewItemsNotice?,
    snackbarHostState: SnackbarHostState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distance = with(LocalDensity.current) { 4.dp.roundToPx() }
    // Retain the leaving content for AnimatedVisibility without shrinking its touch target.
    var lastNotice by remember { mutableStateOf(notice) }
    if (notice != null) lastNotice = notice
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (snackbarHostState.currentSnackbarData != null) {
            SnackbarHost(snackbarHostState) { data -> TimelineNotice(data.visuals.message, neutral = true) }
        }
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -distance },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { -distance },
        ) {
            lastNotice?.let { shown ->
                TimelineNotice(shown.message, onClick = onClick.takeIf { notice?.actionable == true },
                    buttonAppearance = shown.actionable || shown.dismissAfterMillis == 600L)
            }
        }
    }
}
