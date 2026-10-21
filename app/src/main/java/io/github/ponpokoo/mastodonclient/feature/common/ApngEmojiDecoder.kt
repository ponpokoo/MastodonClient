package io.github.ponpokoo.mastodonclient.feature.common

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

// Opt in only for displayed body/reaction emoji. The picker keeps the default static decoder.
internal fun ImageRequest.Builder.animatedEmoji(): ImageRequest.Builder =
    decoderFactory(ApngEmojiDecoder.Factory)
        .memoryCacheKeyExtra("animated-emoji", "apng-v1")

private const val maxEncodedBytes = 8L * 1024 * 1024
private val pngSignature = "89504e470d0a1a0a".decodeHex()

/** Inspect PNG chunks, without consuming the source or mistaking metadata text for acTL. */
internal fun hasApngAnimation(source: BufferedSource): Boolean = source.peek().use { peek ->
    if (!peek.request(8) || peek.readByteString(8) != pngSignature) return false
    var offset = 8L
    while (offset + 12 <= maxEncodedBytes && peek.request(8)) {
        val length = peek.readInt().toLong() and 0xffffffffL
        val type = peek.readUtf8(4)
        if (type == "IDAT" || type == "IEND") return false
        if (length > maxEncodedBytes - offset - 12 || !peek.request(length + 4)) return false
        if (type == "acTL") return length == 8L && peek.readInt() > 0
        peek.skip(length + 4) // payload and CRC
        offset += length + 12
    }
    false
}

internal class ApngEmojiDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        coroutineContext.ensureActive()
        val input = source.source()
        if (input.request(maxEncodedBytes + 1)) throw IOException("APNG emoji exceeds size limit")
        val bytes = input.readByteArray()
        // Bound the canvas before the library allocates frame buffers. IHDR is the first PNG chunk.
        if (bytes.size < 33) throw IOException("Incomplete APNG header")
        val header = ByteBuffer.wrap(bytes)
        val width = header.getInt(16)
        val height = header.getInt(20)
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452 ||
            width <= 0 || height <= 0 || width.toLong() * height > 4L * 1024 * 1024
        ) throw IOException("Invalid or oversized APNG canvas")

        // Decoding continues after Coil closes ImageSource. Own the bytes and return a fresh reader
        // on every restart; a temporary source.file() would already have been deleted on reentry.
        val drawable = APNGDrawable(object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }).apply { setAutoPlay(false) }
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            throw IOException("Unable to decode APNG emoji")
        }
        coroutineContext.ensureActive()
        // A mutable animation belongs to one painter. Coil/Compose starts and stops it with that
        // painter, and another visible occurrence gets its own drawable and callback.
        return DecodeResult(drawable.asImage(shareable = false), isSampled = false)
    }

    internal object Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (hasApngAnimation(result.source.source())) ApngEmojiDecoder(result.source) else null
    }
}
