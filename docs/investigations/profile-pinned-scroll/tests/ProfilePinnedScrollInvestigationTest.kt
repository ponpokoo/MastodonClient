package io.github.ponpokoo.mastodonclient

import android.util.Log
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.domain.model.PreviewCard
import io.github.ponpokoo.mastodonclient.domain.model.ProfileField
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import coil3.SingletonImageLoader

class ProfilePinnedScrollInvestigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var posts: LazyListState
    private lateinit var header: LazyListState
    private val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")

    @Test fun slowScrollWithoutPins() = investigate(0)
    @Test fun slowScrollWithOnePin() = investigate(1)
    @Test fun slowScrollWithThreePins() = investigate(3)
    @Test fun slowScrollBackWithThreePins() = investigate(3, reverse = true)
    @Test fun slowScrollWithThreeShortPins() = investigate(3, lines = 0)
    @Test fun scrollBackWithThreeImagePins() = investigate(3, lines = 0, reverse = true, images = true)
    @Test fun scrollBackWithThreeImagePinsAfterEviction() = investigate(3, lines = 0, reverse = true, images = true, evictImages = true)
    @Test fun rapidScrollDownAndBackWithFourImagePins() = scrollDownAndBackWithFourImagePins(120)
    @Test fun mediumScrollDownAndBackWithFourImagePins() = scrollDownAndBackWithFourImagePins(500)
    @Test fun slowScrollDownAndBackWithFourImagePins() = scrollDownAndBackWithFourImagePins(1200)
    @Test fun rapidScrollDownAndBackWithFourImageAndUrlPins() = scrollDownAndBackWithFourImagePins(120, previewCards = true)
    @Test fun mediumScrollDownAndBackWithFourImageAndUrlPins() = scrollDownAndBackWithFourImagePins(500, previewCards = true)
    @Test fun slowScrollDownAndBackWithFourImageAndUrlPins() = scrollDownAndBackWithFourImagePins(1200, previewCards = true)
    @Test fun headerOriginFlingContinuesIntoPostsWithLongProfile() {
        showProfile(4, 1, images = true, variedImages = true, previewCards = true, longHeader = true)
        val screen = rule.onNodeWithTag("profile_screen")
        val initial = snapshot()
        Log.i("PinnedScrollInvestigation", "longHeader initial=$initial")
        screen.performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.8f), Offset(width * 0.6f, height * 0.2f), 120)
        }
        rule.waitForIdle()
        val fromHeader = snapshot()
        Log.i("PinnedScrollInvestigation", "longHeader afterHeaderFling=$fromHeader")
        // The second identical gesture now starts within the post list.
        screen.performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.8f), Offset(width * 0.6f, height * 0.2f), 120)
        }
        rule.waitForIdle()
        val fromPosts = snapshot()
        Log.i("PinnedScrollInvestigation", "longHeader afterPostFling=$fromPosts")
        assertTrue("Control swipe did not scroll posts: $fromPosts", fromPosts.postIndex > 0 || fromPosts.postOffset > 0)
        assertTrue("Header-origin fling stopped at header boundary: $fromHeader", fromHeader.postIndex > 0 || fromHeader.postOffset > 0)
    }

    private fun scrollDownAndBackWithFourImagePins(durationMillis: Long, previewCards: Boolean = false) {
        showProfile(4, 1, images = true, variedImages = true, previewCards = previewCards)
        if (previewCards) {
            assertTrue("Attachment image missing from fixture", rule.onAllNodesWithTag("media_attachment", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue("URL thumbnail missing from fixture", rule.onAllNodesWithTag("preview_card_image", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        val screen = rule.onNodeWithTag("profile_screen")
        val downGestures = if (previewCards) 5 else 4
        val backGestures = if (previewCards) 7 else 6
        var furthestIndex = 0
        rule.mainClock.autoAdvance = false
        try {
            fun record(direction: String, gesture: Int, frame: Int) {
                val position = snapshot()
                furthestIndex = maxOf(furthestIndex, position.postIndex)
                Log.i("PinnedScrollInvestigation", "fourPins previewCards=$previewCards durationMs=$durationMillis direction=$direction gesture=$gesture frame=$frame position=$position")
            }
            record("down", -1, 0)
            repeat(downGestures) { gesture ->
                // Scroll towards older posts, then start another swipe before the fling settles.
                screen.performTouchInput {
                    swipe(Offset(width * 0.6f, height * 0.9f), Offset(width * 0.6f, height * 0.2f), durationMillis)
                }
                repeat(4) { frame ->
                    rule.mainClock.advanceTimeByFrame()
                    record("down", gesture, frame)
                }
            }
            repeat(90) { frame ->
                rule.mainClock.advanceTimeByFrame()
                record("down-settle", downGestures, frame)
            }
            assertTrue("Scrolling at ${durationMillis}ms did not pass all four pinned posts: index=$furthestIndex", furthestIndex >= 4)
            repeat(backGestures) { gesture ->
                // Start below the tab row so that the post list receives the gesture.
                screen.performTouchInput {
                    swipe(Offset(width * 0.6f, height * 0.2f), Offset(width * 0.6f, height * 0.9f), durationMillis)
                }
                repeat(4) { frame ->
                    rule.mainClock.advanceTimeByFrame()
                    record("back", gesture, frame)
                }
            }
            repeat(120) { frame ->
                rule.mainClock.advanceTimeByFrame()
                record("back-settle", backGestures, frame)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("Post list did not return to the first pin", posts.firstVisibleItemIndex == 0 && posts.firstVisibleItemScrollOffset == 0)
            assertTrue("Header did not return to the top", header.firstVisibleItemIndex == 0 && header.firstVisibleItemScrollOffset == 0)
        }
    }
    @Test fun flingBackAcrossThreePinsToHeader() {
        showProfile(3, 0)
        rule.runOnIdle {
            runBlocking {
                header.scrollToItem(1)
                posts.scrollToItem(3, 100)
            }
        }
        rule.waitForIdle()
        val screen = rule.onNodeWithTag("profile_screen")
        Log.i("PinnedScrollInvestigation", "backFling before=${snapshot()}")
        rule.mainClock.autoAdvance = false
        screen.performTouchInput {
            swipe(Offset(width * 0.6f, height * 0.2f), Offset(width * 0.6f, height * 0.9f), 200)
        }
        repeat(120) { frame ->
            rule.mainClock.advanceTimeByFrame()
            Log.i("PinnedScrollInvestigation", "backFling frame=$frame position=${snapshot()}")
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("Post list did not reach top", posts.firstVisibleItemIndex == 0 && posts.firstVisibleItemScrollOffset == 0)
            assertTrue("Header did not reach top", header.firstVisibleItemIndex == 0 && header.firstVisibleItemScrollOffset == 0)
        }
    }
    @Test fun flingWithThreePins() {
        showProfile(3, 8)
        val screen = rule.onNodeWithTag("profile_screen")
        repeat(3) { gesture ->
            rule.mainClock.autoAdvance = false
            screen.performTouchInput { swipeUp(durationMillis = 200) }
            var previous = snapshot()
            var backwards = 0
            var distance = 0f
            repeat(90) { frame ->
                rule.mainClock.advanceTimeByFrame()
                val current = snapshot()
                val common = previous.items.keys.firstOrNull { it in current.items }
                val travelled = common?.let { previous.items.getValue(it) - current.items.getValue(it) }
                if (travelled != null) {
                    distance += travelled
                    if (travelled < -2f) backwards++
                }
                Log.i("PinnedScrollInvestigation", "fling gesture=$gesture frame=$frame travelled=$travelled before=$previous after=$current")
                previous = current
            }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            assertTrue("Fling moved backwards $backwards frames", backwards == 0)
            assertTrue("Fling did not continue after finger release: $distance", distance > 0f)
        }
    }

    private fun showProfile(pinCount: Int, lines: Int, images: Boolean = false, variedImages: Boolean = false, previewCards: Boolean = false, longHeader: Boolean = false) {
        fun imageUrl(index: Int, preview: Boolean = false): String? {
            if (!images) return null
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pinned-scroll-investigation-${if (preview) "preview-" else ""}$index.png")
            val dimensions = if (preview) listOf(640 to 360, 300 to 450, 640 to 400, 512 to 512)[index]
                else if (variedImages) listOf(800 to 200, 600 to 800, 1000 to 600, 400 to 400)[index] else 800 to 200
            val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (preview) android.graphics.Color.GREEN else android.graphics.Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return Uri.fromFile(file).toString()
        }
        val pinned = List(pinCount) {
            val id = "pin-$it"
            val imageUrl = imageUrl(if (variedImages) it else 0)
            val url = "https://example.test/link-$it"
            val original = status(id, lines)
            original.copy(
                contentHtml = if (previewCards) original.contentHtml + "<p><a href=\"$url\">$url</a></p>" else original.contentHtml,
                mediaAttachments = if (imageUrl == null) emptyList() else listOf(
                    MediaAttachment(id, "image", imageUrl, imageUrl, "合成画像", aspectRatio = null)),
                previewCard = if (previewCards) PreviewCard(url, "URLサムネイルの確認 $it",
                    "画像付き固定投稿のスクロール確認用リンクカード", "link", "example.test",
                    imageUrl(it, preview = true), aspectRatio = null) else null,
            )
        }
        val regular = List(20) { status("post-$it", lines) }
        rule.setContent {
            MaterialTheme {
                posts = rememberLazyListState()
                header = rememberLazyListState()
                if (images) {
                    LaunchedEffect(posts) {
                        snapshotFlow {
                            posts.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) }
                        }.collect { Log.i("PinnedScrollInvestigation", "imageLayout=$it") }
                    }
                }
                ProfileContent(
                    state = ProfileUiState(profile = UserProfile(author, "",
                        if (longHeader) "<p>" + List(22) { "自己紹介の行 $it" }.joinToString("<br>") + "</p>" else "",
                        0, 0, 20, regular, endReached = true, pinnedStatuses = pinned,
                        fields = if (longHeader) listOf(
                            ProfileField("ブログ", "<a href=\"https://example.test/blog\">https://example.test/blog</a>", null),
                            ProfileField("外部プロフィール", "https://example.test/profile", null),
                        ) else emptyList())),
                    padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                    onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                    onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    listStates = listOf(posts, rememberLazyListState(), rememberLazyListState()),
                    headerListState = header,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun investigate(pinCount: Int, lines: Int = 8, reverse: Boolean = false, images: Boolean = false, evictImages: Boolean = false) {
        showProfile(pinCount, lines, images)
        val screen = rule.onNodeWithTag("profile_screen")
        val bounds = screen.fetchSemanticsNode().boundsInRoot
        val upStartY = bounds.height * 0.9f
        val upStep = bounds.height * 0.055f
        if (reverse) {
            repeat(5) {
                screen.performTouchInput {
                    swipe(Offset(width * 0.6f, upStartY), Offset(width * 0.6f, upStartY - upStep * 12), 1200)
                    advanceEventTime(200)
                }
                rule.waitForIdle()
            }
        }
        if (evictImages) {
            rule.runOnIdle {
                SingletonImageLoader.get(InstrumentationRegistry.getInstrumentation().targetContext).memoryCache?.clear()
            }
        }
        val startY = if (reverse) bounds.height * 0.15f else upStartY
        val step = if (reverse) -upStep else upStep
        var previous = snapshot()
        var stalls = 0
        repeat(5) { gesture ->
            screen.performTouchInput { down(Offset(width * 0.6f, startY)) }
            repeat(12) { move ->
                screen.performTouchInput {
                    moveTo(Offset(width * 0.6f, startY - step * (move + 1)), delayMillis = 80)
                }
                rule.waitForIdle()
                val current = snapshot()
                val common = previous.items.keys.firstOrNull { it in current.items }
                val travelled = common?.let { previous.items.getValue(it) - current.items.getValue(it) }
                Log.i("PinnedScrollInvestigation", "pins=$pinCount lines=$lines reverse=$reverse gesture=$gesture move=$move step=$step travelled=$travelled before=$previous after=$current")
                if (move > 0 && travelled != null && travelled / step < 0.5f) stalls++
                previous = current
            }
            screen.performTouchInput { advanceEventTime(200); up() }
            rule.waitForIdle()
            previous = snapshot()
        }
        assertTrue("pins=$pinCount: $stalls scroll steps moved less than half the finger distance", stalls == 0)
    }

    private fun snapshot(): Position {
        // A long header can keep the pager entirely outside the lazy composition.
        val pagerY = rule.onAllNodesWithTag("profile_status_pager").fetchSemanticsNodes()
            .firstOrNull()?.boundsInRoot?.top ?: 0f
        var result: Position? = null
        rule.runOnIdle {
            result = Position(header.firstVisibleItemIndex, header.firstVisibleItemScrollOffset,
                posts.firstVisibleItemIndex, posts.firstVisibleItemScrollOffset,
                posts.layoutInfo.visibleItemsInfo.associate { it.key to (pagerY + it.offset) })
        }
        return checkNotNull(result)
    }

    private data class Position(val headerIndex: Int, val headerOffset: Int,
        val postIndex: Int, val postOffset: Int, val items: Map<Any, Float>)

    private fun status(id: String, lines: Int) = TimelineStatus(
        timelineId = id, statusId = id, createdAt = "2026-10-05T00:00:00Z", author = author,
        boostedBy = null, contentHtml = "<p>$id</p>" + List(lines) { "<p>スクロール確認の本文 $it</p>" }.joinToString(""),
        spoilerText = "", sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
    )
}
