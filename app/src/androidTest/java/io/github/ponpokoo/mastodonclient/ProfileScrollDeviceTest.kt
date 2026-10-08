package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.timeline.animateToTimelineTop
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileScrollDeviceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var posts: LazyListState
    private lateinit var header: LazyListState

    @Test fun headerFlingContinuesIntoPinnedPostsAfterFingerRelease() {
        showLongProfile()
        rule.mainClock.autoAdvance = false
        try {
            flingFromHeader()
            rule.runOnIdle {
                // The drag ends inside the header; only its remaining fling can reach posts.
                assertEquals(0, posts.firstVisibleItemIndex)
                assertEquals(0, posts.firstVisibleItemScrollOffset)
                assertTrue(header.canScrollForward)
            }
            rule.mainClock.advanceTimeBy(2000)
            rule.runOnIdle { assertTrue(posts.firstVisibleItemIndex > 0 || posts.firstVisibleItemScrollOffset > 0) }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun touchingPostsInterruptsHeaderOriginFlingAndAllowsReverseDrag() {
        showLongProfile()
        rule.mainClock.autoAdvance = false
        try {
            flingFromHeader()
            rule.mainClock.advanceTimeUntil(2000) {
                !header.canScrollForward && posts.firstVisibleItemScrollOffset > 100
            }
            rule.runOnIdle { assertTrue(header.isScrollInProgress) }
            val pager = rule.onNodeWithTag("profile_status_pager")
            pager.performTouchInput { down(Offset(width * 0.6f, height * 0.3f)) }
            rule.mainClock.advanceTimeBy(100)
            val stopped = position()
            rule.mainClock.advanceTimeBy(200)
            assertEquals("Holding a finger should stop the inherited fling", stopped, position())
            pager.performTouchInput {
                moveTo(Offset(width * 0.6f, height * 0.4f), delayMillis = 300)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { assertTrue("Reverse drag should scroll posts before revealing the header", !header.canScrollForward) }
            assertTrue("Reverse drag did not move the post list", position() < stopped)
            pager.performTouchInput { advanceEventTime(200); up() }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun reverseDragStartingOnTabsReturnsPostsBeforeRevealingProfile() {
        showLongProfile()
        rule.runOnIdle { runBlocking { header.scrollToItem(1); posts.scrollToItem(12) } }
        val before = position()
        rule.onNodeWithTag("profile_status_tab_posts").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + 180f), delayMillis = 600)
            advanceEventTime(200)
            up()
        }
        rule.runOnIdle {
            assertTrue("Posts should still be away from the top", posts.canScrollBackward)
            assertTrue("Profile was revealed before posts reached the top", !header.canScrollForward)
        }
        assertTrue("Dragging the tab row should return posts", position() < before)
    }

    @Test fun sharedReturnReachesProfileTopWithinOneAnimation() {
        showLongProfile(showReturnControl = true)
        rule.runOnIdle { runBlocking { header.scrollToItem(1); posts.scrollToItem(18, 24) } }
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText("Go to top").performClick()
            rule.mainClock.advanceTimeBy(600)
            rule.runOnIdle {
                assertEquals(0, posts.firstVisibleItemIndex)
                assertEquals(0, posts.firstVisibleItemScrollOffset)
                assertEquals(0, header.firstVisibleItemIndex)
                assertEquals(0, header.firstVisibleItemScrollOffset)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun gestureOnProfileInterruptsTheEntireSharedReturn() {
        showLongProfile(showReturnControl = true)
        rule.runOnIdle { runBlocking { header.scrollToItem(1); posts.scrollToItem(18, 24) } }
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText("Go to top").performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.onNodeWithTag("profile_screen").performTouchInput {
                swipe(Offset(width * 0.6f, height * 0.8f), Offset(width * 0.6f, height * 0.4f), 400)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        val postPosition = position()
        var headerPosition = 0L
        rule.runOnIdle {
            headerPosition = header.firstVisibleItemIndex * 1_000_000L + header.firstVisibleItemScrollOffset
            assertTrue(header.canScrollBackward || posts.canScrollBackward)
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(postPosition, position())
        rule.runOnIdle {
            assertEquals(headerPosition, header.firstVisibleItemIndex * 1_000_000L + header.firstVisibleItemScrollOffset)
        }
    }

    private fun position(): Long {
        var position = 0L
        rule.runOnIdle { position = posts.firstVisibleItemIndex * 1_000_000L + posts.firstVisibleItemScrollOffset }
        return position
    }

    private fun flingFromHeader() {
        rule.onNodeWithTag("profile_screen").performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.8f), Offset(width * 0.6f, height * 0.2f), 120)
        }
    }

    private fun showLongProfile(showReturnControl: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun image(name: String, width: Int, height: Int): String {
            val file = File(context.cacheDir, "profile-scroll-$name.png")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return Uri.fromFile(file).toString()
        }
        val attachment = image("attachment", 800, 200)
        val thumbnail = image("thumbnail", 640, 360)
        val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
        fun status(id: String) = TimelineStatus(
            timelineId = id, statusId = id, createdAt = "2026-10-05T00:00:00Z", author = author,
            boostedBy = null, contentHtml = "<p>$id</p>", spoilerText = "", sensitive = false,
            visibility = "public", url = null, repliesCount = 0, boostsCount = 0, favouritesCount = 0,
            mediaAttachments = emptyList(),
        )
        val pinned = List(4) { index ->
            val url = "https://example.test/link-$index"
            status("pin-$index").copy(
                contentHtml = "<p>固定投稿 $index <a href=\"$url\">$url</a></p>",
                mediaAttachments = listOf(MediaAttachment("media-$index", "image", attachment, attachment, "合成画像", aspectRatio = null)),
                previewCard = PreviewCard(url, "リンク $index", "テスト用カード", "link", "example.test", thumbnail, aspectRatio = null),
            )
        }
        val profile = UserProfile(author, "", "<p>" + List(22) { "自己紹介の行 $it" }.joinToString("<br>") + "</p>",
            0, 0, 24, List(20) { status("post-$it") }, endReached = true, pinnedStatuses = pinned)
        rule.setContent {
            MaterialTheme {
                posts = rememberLazyListState()
                header = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        ProfileContent(
                            state = ProfileUiState(profile = profile), padding = PaddingValues(),
                            onRetry = {}, onRefresh = {}, onStatusClick = {}, onOpenLink = {}, onReply = {},
                            onBoost = {}, onQuote = { _, _ -> }, onFavourite = {}, onReact = { _, _ -> },
                            onAccountClick = {}, onMediaClick = { _, _ -> },
                            listStates = listOf(posts, rememberLazyListState(), rememberLazyListState()), headerListState = header,
                        )
                    }
                    if (showReturnControl) {
                        Button(onClick = { scope.launch { posts.animateToTimelineTop(header) } }) { Text("Go to top") }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
}
