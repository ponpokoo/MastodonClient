package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Selection feedback follows a drag; data selection is published only after settling or a tap. */
@Composable
internal fun rememberSwipeTabs(
    selectedPage: Int,
    pageCount: Int,
    onSelectPage: (Int) -> Unit,
): SwipeTabs {
    val pager = rememberPagerState(initialPage = selectedPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val latestSelect by rememberUpdatedState(onSelectPage)
    val tabs = remember(pager, scope) { SwipeTabs(pager, scope, selectedPage) { latestSelect(it) } }
    val isDragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(selectedPage, tabs) { tabs.syncSelection(selectedPage) }
    LaunchedEffect(isDragged, tabs) { if (isDragged) tabs.interruptAnimation() }
    LaunchedEffect(tabs) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, tabs.animationTarget) }
            .collect { (page, scrolling, target) ->
                if (!scrolling && target == null) tabs.selectSettledPage(page)
            }
    }
    return tabs
}

internal class SwipeTabs(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
    initialPage: Int,
    private val onSelectPage: (Int) -> Unit,
) {
    internal var animationTarget by mutableStateOf<Int?>(null)
        private set
    private var committedPage = initialPage
    private var animation: Job? = null
    private var animationGeneration = 0L

    val selectedPage: Int get() = animationTarget ?: pagerState.currentPage
    val isMoving: Boolean get() = animationTarget != null || pagerState.isScrollInProgress

    fun selectPage(page: Int) {
        // Suppress intermediate pages before publishing the tap to the ViewModel.
        animateTo(page)
        publishSelection(page)
    }

    internal fun syncSelection(page: Int) {
        // A callback echo must not restart an animation or pull an active gesture backwards.
        if (page == committedPage) return
        committedPage = page
        animateTo(page)
    }

    internal fun selectSettledPage(page: Int) = publishSelection(page)

    internal fun interruptAnimation() {
        animationGeneration++
        animation?.cancel()
        animation = null
        animationTarget = null
    }

    private fun publishSelection(page: Int) {
        if (page == committedPage) return
        committedPage = page
        onSelectPage(page)
    }

    private fun animateTo(page: Int) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage == page &&
            pagerState.currentPageOffsetFraction == 0f) {
            interruptAnimation()
            return
        }
        val generation = ++animationGeneration
        animation?.cancel()
        animationTarget = page
        animation = scope.launch {
            try {
                pagerState.animateScrollToPage(page)
            } finally {
                if (generation == animationGeneration) animationTarget = null
            }
        }
    }
}
