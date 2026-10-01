package io.github.ponpokoo.mastodonclient.feature.timeline

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Settle a completed animation at the beginning, while respecting cancellation by a new gesture. */
internal suspend fun LazyListState.animateToTimelineTop() {
    animateScrollToItem(0)
    currentCoroutineContext().ensureActive()
    if (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0) {
        scrollToItem(0)
    }
}
