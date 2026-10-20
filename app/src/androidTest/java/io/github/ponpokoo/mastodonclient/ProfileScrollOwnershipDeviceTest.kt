package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import kotlin.math.abs

class ProfileScrollOwnershipDeviceTest {
    @get:Rule val rule = createComposeRule()
    private enum class Screen { Home, Profile }
    private val screen = mutableStateOf(Screen.Home)
    private lateinit var posts: LazyListState
    private lateinit var header: LazyListState
    private var velocity = 0f
    private var flingDistance = 0f
    private val observer = object : NestedScrollConnection {
        override suspend fun onPreFling(available: Velocity): Velocity {
            velocity = available.y
            return Velocity.Zero
        }

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.SideEffect) flingDistance += available.y
            return Offset.Zero
        }
    }

    @Test fun postBodyFlingMatchesHomeWithAndWithoutTheHeader() {
        show()
        for (duration in listOf(80L, 160L, 320L)) {
            val home = measure(Screen.Home, duration)
            assertTrue("The control must have fling velocity", abs(home.first) > 100f)
            for (collapsed in listOf(false, true)) {
                val profile = measure(Screen.Profile, duration, collapsed)
                assertEquals("Fling velocity differs at $duration ms, collapsed=$collapsed",
                    home.first, profile.first, abs(home.first) * .05f)
                assertEquals("Fling travel differs at $duration ms, collapsed=$collapsed",
                    home.second, profile.second, abs(home.second) * .05f + 1f)
            }
        }
    }

    @Test fun touchingEitherRegionStopsAFlingStartedOnThePostBody() {
        show()
        for (onHeader in listOf(true, false)) {
            select(Screen.Profile)
            val start = postTouchPoint()
            rule.mainClock.autoAdvance = false
            try {
                probe().performTouchInput { swipe(start, start - Offset(0f, 160f), 100) }
                rule.mainClock.advanceTimeBy(64)
                rule.runOnIdle {
                    assertTrue(header.isScrollInProgress)
                    assertTrue("The header must remain visible", header.canScrollForward)
                }
                val headerBounds = rule.onNodeWithTag("profile_header").fetchSemanticsNode().boundsInRoot
                val point = if (onHeader) Offset(headerBounds.width * .9f, headerBounds.bottom * .5f) else start
                probe().performTouchInput { down(point) }
                rule.mainClock.advanceTimeBy(32)
                var stopped = emptyList<Int>()
                rule.runOnIdle { stopped = position() }
                rule.mainClock.advanceTimeBy(160)
                rule.runOnIdle { assertEquals("Holding either region must stop both lists", stopped, position()) }
            } finally {
                probe().performTouchInput { cancel() }
                rule.mainClock.autoAdvance = true
            }
            rule.waitForIdle()
        }
    }

    @Test fun accessibilityCanScrollPostsBeyondTheHeaderAndReturnToTheProfileTop() {
        show()
        select(Screen.Profile, collapsed = true)
        val profile = rule.onNodeWithTag("profile_scroll")
        fun assertCanScrollForward() {
            val range = profile.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("More posts must remain reachable after the header collapses", range.value() < range.maxValue())
        }
        assertCanScrollForward()
        profile.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 600f)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(posts.canScrollBackward)
            assertTrue(!header.canScrollForward)
        }
        assertCanScrollForward()
        profile.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, -100_000f)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf(0, 0, 0, 0), position())
        }
    }

    @Test fun reverseFlingFromPostsContinuesThroughTheProfileHeader() {
        show()
        select(Screen.Profile, collapsed = true)
        rule.runOnIdle { runBlocking { posts.scrollToItem(1, 100) } }
        probe().performTouchInput {
            swipe(Offset(width * .6f, height * .25f), Offset(width * .6f, height * .85f), 200)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf(0, 0, 0, 0), position()) }
    }

    private fun measure(target: Screen, duration: Long, collapsed: Boolean = false): Pair<Float, Float> {
        select(target, collapsed)
        val start = postTouchPoint()
        rule.mainClock.autoAdvance = false
        try {
            velocity = 0f
            flingDistance = 0f
            probe().performTouchInput { swipe(start, start - Offset(0f, 240f), duration) }
            rule.mainClock.advanceTimeBy(3000)
            rule.runOnIdle { assertTrue(!posts.isScrollInProgress && !header.isScrollInProgress) }
            return velocity to flingDistance
        } finally { rule.mainClock.autoAdvance = true }
    }

    private fun select(target: Screen, collapsed: Boolean = false) {
        rule.runOnIdle { screen.value = target }
        rule.waitForIdle()
        rule.runOnIdle { runBlocking {
            posts.scrollToItem(0)
            if (target == Screen.Profile) header.scrollToItem(if (collapsed) 1 else 0)
        } }
        rule.waitForIdle()
    }

    private fun probe() = rule.onNodeWithTag("scroll_probe")

    private fun postTouchPoint(): Offset {
        val text = rule.onNodeWithText("post-0", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val root = probe().fetchSemanticsNode().boundsInRoot
        val point = Offset(text.center.x, minOf(text.bottom - 24f, root.bottom - 120f))
        assertTrue("Gesture must start inside the visible post body", text.contains(point))
        return point
    }

    private fun position() = listOf(header.firstVisibleItemIndex, header.firstVisibleItemScrollOffset,
        posts.firstVisibleItemIndex, posts.firstVisibleItemScrollOffset)

    private fun show() {
        val author = StatusAuthor("author", "Test", "test@example.test", "")
        val statuses = List(100) { index ->
            TimelineStatus("post-$index", "post-$index", "2026-10-20T00:00:00Z", author, null,
                "<p>post-$index<br>" + List(28) { "本文の行 $it" }.joinToString("<br>") + "</p>",
                "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
        }
        rule.setContent {
            MaterialTheme {
                key(screen.value) {
                    posts = rememberLazyListState()
                    header = rememberLazyListState()
                    Box(Modifier.fillMaxSize().testTag("scroll_probe").nestedScroll(observer)) {
                        if (screen.value == Screen.Home) {
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
        }
        rule.waitForIdle()
    }
}
