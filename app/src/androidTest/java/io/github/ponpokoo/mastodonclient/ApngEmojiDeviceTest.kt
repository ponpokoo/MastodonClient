package io.github.ponpokoo.mastodonclient

import android.graphics.drawable.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.DrawableImage
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.penfeizhou.animation.apng.APNGDrawable
import io.github.ponpokoo.mastodonclient.domain.model.EmojiReaction
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.feature.common.animatedEmoji
import io.github.ponpokoo.mastodonclient.feature.status.StatusCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ApngEmojiDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLoader: ImageLoader? = null
    private var loader: ImageLoader? = null
    private val animations = Collections.synchronizedList(mutableListOf<APNGDrawable>())
    private val errors = Collections.synchronizedList(mutableListOf<Throwable>())
    private val url = "https://emoji.test/palette.png"

    private fun createLoader(): ImageLoader {
        // Optional local fixture permits retesting a reported public image without network in tests.
        val fixture = InstrumentationRegistry.getArguments().getString("apngFixture")
        val bytes = fixture?.let { File(context.filesDir, it).readBytes() } ?: paletteApng()
        return ImageLoader.Builder(context).components {
            add(AnimatedImageDecoder.Factory())
            add(Fetcher.Factory<Uri> { data, options, _ ->
                if (data.toString() != url) return@Factory null
                Fetcher { SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem),
                    "image/png", DataSource.NETWORK) }
            })
        }.eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                ((result.image as? DrawableImage)?.drawable as? APNGDrawable)?.let(animations::add)
            }
            override fun onError(request: ImageRequest, result: ErrorResult) { errors.add(result.throwable) }
        }).build().also { loader = it }
    }

    @After fun cleanup() {
        previousLoader?.let(SingletonImageLoader::setUnsafe)
        rule.runOnUiThread { animations.forEach { it.stop() } }
        loader?.shutdown()
    }

    @Test fun bodyAndReactionAnimateAndRestartAfterScrollingOffscreen() {
        previousLoader = SingletonImageLoader.get(context)
        SingletonImageLoader.setUnsafe(createLoader())
        val state = LazyListState()
        val status = TimelineStatus(
            "fixture", "fixture", "2026-10-21T00:00:00Z", StatusAuthor("author", "Author", "author@example.test", ""),
            null, "<p>:fixture:</p>", "", false, "public", null, 0, 0, 0,
            reactions = listOf(EmojiReaction("fixture", 1, false, url, emptySet())),
            mediaAttachments = emptyList(), customEmojis = mapOf("fixture" to url),
        )
        rule.setContent {
            MaterialTheme {
                Column {
                    LazyColumn(Modifier.width(320.dp).height(350.dp), state = state) {
                        item(key = "target") {
                            StatusCard(status, onStatusClick = null, onUnavailableAction = {},
                                authorAvatarResource = android.R.drawable.sym_def_app_icon)
                        }
                        items(40) { Text("Filler $it", Modifier.height(80.dp)) }
                    }
                }
            }
        }
        rule.waitUntil(10_000) { animations.size >= 2 || errors.isNotEmpty() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertAnimationChanges(rule.onNodeWithText(":fixture:"))
        assertAnimationChanges(rule.onNodeWithContentDescription("fixture", useUnmergedTree = true))
        val firstDrawables = animations.toList()
        runBlocking { withContext(Dispatchers.Main) { state.scrollToItem(25) } }
        rule.waitUntil(10_000) { firstDrawables.none { it.isRunning } }
        runBlocking { withContext(Dispatchers.Main) { state.scrollToItem(0) } }
        rule.waitUntil(10_000) { animations.size >= 4 || errors.isNotEmpty() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertAnimationChanges(rule.onNodeWithText(":fixture:"))
        assertAnimationChanges(rule.onNodeWithContentDescription("fixture", useUnmergedTree = true))
    }

    @Test fun staticAndAnimatedRequestsForSameUrlStayIndependentInBothOrders() = runBlocking {
        val imageLoader = createLoader()
        suspend fun load(animated: Boolean): SuccessResult {
            val request = ImageRequest.Builder(context).data(url).size(128, 128)
                .apply { if (animated) animatedEmoji() }.build()
            val result = imageLoader.execute(request)
            assertTrue(result.toString(), result is SuccessResult)
            return result as SuccessResult
        }
        // This is the same default loading path used by picker tiles/previews, even when static_url
        // is absent or equal to the original URL.
        assertFalse((load(false).image as? DrawableImage)?.drawable is Animatable)
        assertTrue((load(true).image as DrawableImage).drawable is APNGDrawable)
        imageLoader.memoryCache!!.clear()
        assertTrue((load(true).image as DrawableImage).drawable is APNGDrawable)
        assertFalse((load(false).image as? DrawableImage)?.drawable is Animatable)
    }

    private fun assertAnimationChanges(node: SemanticsNodeInteraction) {
        val samples = mutableSetOf<Int>()
        rule.waitUntil(8_000) {
            val pixels = node.captureToImage().toPixelMap()
            var hash = 1
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                hash = 31 * hash + pixels[x, y].hashCode()
            }
            samples.add(hash)
            samples.size >= 3
        }
    }

    /** Original indexed-color APNG fixture: three distinct frames and a transparent palette entry. */
    private fun paletteApng(): ByteArray {
        val png = Buffer().write("89504e470d0a1a0a".decodeHex())
        fun chunk(type: String, payload: ByteArray) {
            val crc = CRC32().apply { update(type.toByteArray()); update(payload) }
            png.writeInt(payload.size).writeUtf8(type).write(payload).writeInt(crc.value.toInt())
        }
        chunk("IHDR", Buffer().writeInt(16).writeInt(16).write(byteArrayOf(8, 3, 0, 0, 0)).readByteArray())
        chunk("PLTE", byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0))
        chunk("tRNS", byteArrayOf(-1, -1, -1, 0))
        chunk("acTL", Buffer().writeInt(3).writeInt(0).readByteArray())
        var sequence = 0
        repeat(3) { frame ->
            chunk("fcTL", Buffer().writeInt(sequence++).writeInt(16).writeInt(16)
                .writeInt(0).writeInt(0).writeShort(150).writeShort(1000)
                .writeByte(0).writeByte(0).readByteArray())
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output).use { compressed ->
                repeat(16) { row ->
                    compressed.write(0)
                    repeat(16) { column -> compressed.write(if (row == 0 && column == 0) 3 else frame) }
                }
            }
            if (frame == 0) chunk("IDAT", output.toByteArray())
            else chunk("fdAT", Buffer().writeInt(sequence++).write(output.toByteArray()).readByteArray())
        }
        chunk("IEND", byteArrayOf())
        return png.readByteArray()
    }
}
