package io.github.ponpokoo.mastodonclient

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineContent
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileTopPostScrollInvestigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var posts: LazyListState
    private lateinit var header: LazyListState
    private var label = ""
    private var distance = 0f
    private var velocity = 0f
    private val observer = object : NestedScrollConnection {
        override suspend fun onPreFling(available: Velocity): Velocity {
            velocity = available.y
            Log.i("TopPostInertia", "$label initialVelocity=$velocity")
            return Velocity.Zero
        }
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.SideEffect) distance += available.y
            return Offset.Zero
        }
    }

    @Test fun profileTopPostBodyVelocity() = compare(home = false)
    @Test fun profileCollapsedPostBodyVelocity() = compare(home = false, collapsed = true)
    @Test fun actualHomePostBodyVelocity() = compare(home = true)

    private fun compare(home: Boolean, collapsed: Boolean = false) {
        show(home)
        if (collapsed) rule.runOnIdle { runBlocking { header.scrollToItem(1) } }
        val text = rule.onNodeWithText("post-0", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("probe").fetchSemanticsNode().boundsInRoot
        val start = Offset(text.center.x, minOf(text.bottom - 24f, root.bottom - 120f))
        assertTrue("Touch starts inside the visible first post body", text.contains(start))
        for (duration in listOf(80L, 160L, 320L)) {
            for (live in listOf(false, true)) {
                rule.runOnIdle { runBlocking { posts.scrollToItem(0); if (!home) header.scrollToItem(if (collapsed) 1 else 0) } }
                label = "${if(home) "home" else if (collapsed) "profileCollapsed" else "profileTop"}/$duration/live=$live"
                rule.mainClock.autoAdvance = false
                try {
                    distance = 0f
                    gesture(start, 240f, duration, live)
                    rule.mainClock.advanceTimeBy(3000)
                    rule.runOnIdle {
                        Log.i("TopPostInertia", "$label velocity=$velocity distance=$distance position=${position()} headerBusy=${header.isScrollInProgress} postsBusy=${posts.isScrollInProgress}")
                    }
                } finally { rule.mainClock.autoAdvance = true }
                rule.waitForIdle()
            }
        }
    }

    @Test fun touchingHeaderDuringPostOriginFling() = interrupt(onHeader = true)
    @Test fun touchingPostDuringPostOriginFling() = interrupt(onHeader = false)

    private fun interrupt(onHeader: Boolean) {
        show(home = false)
        val root = rule.onNodeWithTag("probe").fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText("post-0", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val start = Offset(text.center.x, minOf(text.bottom - 24f, root.bottom - 120f))
        assertTrue(text.contains(start))
        rule.mainClock.autoAdvance = false
        try {
            label = "interrupt/onHeader=$onHeader"
            gesture(start, 160f, 100, live = false)
            rule.mainClock.advanceTimeBy(64)
            rule.runOnIdle {
                Log.i("TopPostInertia", "$label beforeTouch position=${position()} headerBusy=${header.isScrollInProgress} postsBusy=${posts.isScrollInProgress}")
                assertTrue("The outer list owns this fling", header.isScrollInProgress)
                assertTrue("Profile header must still be visible", header.canScrollForward)
            }
            val headerBounds = rule.onNodeWithTag("profile_header").fetchSemanticsNode().boundsInRoot
            val point = if (onHeader) Offset(root.width * .9f, headerBounds.bottom * .5f) else start
            rule.onNodeWithTag("probe").performTouchInput { down(point) }
            rule.mainClock.advanceTimeBy(32)
            var before = ""
            rule.runOnIdle { before = position() }
            rule.mainClock.advanceTimeBy(160)
            rule.runOnIdle {
                val after = position()
                Log.i("TopPostInertia", "$label holding before=$before after=$after headerBusy=${header.isScrollInProgress} postsBusy=${posts.isScrollInProgress}")
                assertEquals("Touch should stop motion", before, after)
            }
        } finally {
            rule.onNodeWithTag("probe").performTouchInput { cancel() }
            rule.mainClock.autoAdvance = true
        }
    }

    private fun position() = "h=${header.firstVisibleItemIndex}:${header.firstVisibleItemScrollOffset},p=${posts.firstVisibleItemIndex}:${posts.firstVisibleItemScrollOffset}"

    private fun gesture(start: Offset, delta: Float, duration: Long, live: Boolean) {
        val root = rule.onNodeWithTag("probe")
        if (!live) {
            root.performTouchInput { swipe(start, start - Offset(0f, delta), duration) }
        } else {
            val steps = (duration / 16).toInt().coerceAtLeast(2)
            root.performTouchInput { down(start) }
            rule.mainClock.advanceTimeByFrame()
            repeat(steps) { index ->
                root.performTouchInput { moveTo(start - Offset(0f, delta * (index + 1) / steps), delayMillis = 1) }
                rule.mainClock.advanceTimeByFrame()
            }
            root.performTouchInput { up() }
        }
    }

    private fun show(home: Boolean) {
        val author = StatusAuthor("author", "Test", "test@example.test", "")
        val statuses = List(100) { index ->
            TimelineStatus("post-$index", "post-$index", "2026-10-20T00:00:00Z", author, null,
                "<p>post-$index<br>" + List(28) { "本文の行 $it" }.joinToString("<br>") + "</p>",
                "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        }
        rule.setContent {
            MaterialTheme {
                posts = rememberLazyListState()
                header = rememberLazyListState()
                Box(Modifier.fillMaxSize().testTag("probe").nestedScroll(observer)) {
                    if (home) {
                        TimelineContent(
                            state = TimelineUiState(statuses = statuses, isInitialLoading = false, endReached = true),
                            padding = PaddingValues(), listState = posts, onRefresh = {}, onRetry = {}, onLoadMore = {},
                            onStatusClick = {}, onAccountClick = {}, onMediaClick = { _, _ -> }, onOpenLink = {},
                            onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {}, onBookmark = {},
                            onReact = { _, _ -> }, onVotePoll = { _, _ -> }, onMoreClick = {}, onUnavailableAction = {},
                            preferences = AppPreferences(),
                        )
                    } else {
                        ProfileContent(
                            state = ProfileUiState(profile = UserProfile(author, "", "<p>自己紹介</p>", 0, 0, 100, statuses, endReached = true)),
                            padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {}, onOpenLink = {},
                            onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                            onAccountClick = {}, onMediaClick = { _, _ -> },
                            listStates = listOf(posts, rememberLazyListState(), rememberLazyListState()), headerListState = header,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }
}
