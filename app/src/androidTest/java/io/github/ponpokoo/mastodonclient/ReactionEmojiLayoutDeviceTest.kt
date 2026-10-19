package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ReactionEmojiLayoutDeviceTest {
    @get:Rule val rule = createComposeRule()
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private var gate = CompletableDeferred<Unit>()
    private var lastGate = CompletableDeferred<Unit>()
    private val completed = AtomicInteger()
    private val listState = LazyListState()
    private val reactions = (0 until 12).map { index ->
        EmojiReaction("emoji$index", 1, false, "https://emoji.test/$index", emptySet())
    }

    @After fun cleanup() {
        gate.complete(Unit); lastGate.complete(Unit)
        previousLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
    }

    @Test fun dimensionsCommitTogetherAndSurviveReentryDespiteImageReloadsAndFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(Fetcher.Factory<Uri> { data, _, _ ->
                if (!data.toString().startsWith("https://emoji.test/")) return@Factory null
                Fetcher {
                    gate.await()
                    val index = data.toString().substringAfterLast('/').toInt()
                    if (index == 11) lastGate.await()
                    completed.incrementAndGet()
                    if (index == 10) error("Fixture failure")
                    val width = if (index < 8 || index == 11) 160 else 8
                    val bitmap = Bitmap.createBitmap(width, 32, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.MAGENTA)
                    ImageFetchResult(bitmap.asImage(), false, DataSource.DISK)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val status = TimelineStatus(
            "fixture", "fixture", "2026-10-19T00:00:00Z", StatusAuthor("author", "Author", "author@example.test", ""),
            null, "<p>Reaction fixture</p>", "", false, "public", null, 0, 0, 0,
            reactions = reactions, mediaAttachments = emptyList(),
        )
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.width(320.dp).height(300.dp), state = listState) {
                    item(key = "target") {
                        StatusCard(status, onStatusClick = null, onUnavailableAction = {},
                            authorAvatarResource = android.R.drawable.sym_def_app_icon)
                    }
                    items(40) { Text("Filler $it", Modifier.height(80.dp)) }
                }
            }
        }
        fun rectangles() = rule.onAllNodesWithTag("displayed_reaction").fetchSemanticsNodes().map { it.boundsInRoot }
        fun firstImageWidth() = rule.onNodeWithContentDescription("emoji0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val initial = rectangles()
        val initialWidth = firstImageWidth()
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(10_000) { completed.get() == 11 }
        assertEquals(initial, rectangles())
        rule.runOnIdle { lastGate.complete(Unit) }
        rule.waitUntil(10_000) { firstImageWidth() > initialWidth * 4 }
        val resolved = rectangles()
        assertNotEquals(initial, resolved)
        rule.onNodeWithContentDescription("emoji8", useUnmergedTree = true).assertExists()

        fun scrollTo(index: Int, offset: Int = 0) {
            runBlocking { withContext(Dispatchers.Main) { listState.scrollToItem(index, offset) } }
            rule.waitForIdle()
        }
        scrollTo(0, 83)
        val atReadingPosition = rectangles()
        scrollTo(25)
        loader!!.memoryCache!!.clear()
        gate = CompletableDeferred(); lastGate = CompletableDeferred(); completed.set(0)
        scrollTo(0, 83)
        // No image can complete yet, including the previously failing URL.
        assertEquals(atReadingPosition, rectangles())
        rule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(83, listState.firstVisibleItemScrollOffset)
        }
        rule.runOnIdle { gate.complete(Unit); lastGate.complete(Unit) }
        rule.waitUntil(10_000) { completed.get() == 12 }
        assertEquals(atReadingPosition, rectangles())
        rule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(83, listState.firstVisibleItemScrollOffset)
        }
    }
}
