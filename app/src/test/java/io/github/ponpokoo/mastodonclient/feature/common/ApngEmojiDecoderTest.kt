package io.github.ponpokoo.mastodonclient.feature.common

import okio.Buffer
import okio.ByteString.Companion.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApngEmojiDecoderTest {
    private fun png() = Buffer().write("89504e470d0a1a0a".decodeHex())
    private fun Buffer.chunk(type: String, payload: Buffer = Buffer()): Buffer =
        writeInt(payload.size.toInt()).writeUtf8(type).writeAll(payload).let { writeInt(0) }

    @Test fun findsAnimationAfterMetadataWithoutConsumingSource() {
        val source = png().chunk("IHDR", Buffer().write(ByteArray(13)))
            .chunk("tEXt", Buffer().writeUtf8("Metadata before animation"))
            .chunk("acTL", Buffer().writeInt(10).writeInt(0))
        val original = source.snapshot()
        assertTrue(hasApngAnimation(source))
        assertEquals(original, source.readByteString())
    }

    @Test fun staticPngAndGifStayWithExistingDecoders() {
        assertFalse(hasApngAnimation(png().chunk("IDAT")))
        assertFalse(hasApngAnimation(Buffer().writeUtf8("GIF89a")))
    }

    @Test fun animationMarkerInTextOrAfterImageDataIsNotAnAnimationHeader() {
        assertFalse(hasApngAnimation(png().chunk("tEXt", Buffer().writeUtf8("acTL"))
            .chunk("IDAT").chunk("acTL", Buffer().writeInt(10).writeInt(0))))
    }

    @Test fun truncatedAndUnreasonableChunksDoNotReadPastInput() {
        assertFalse(hasApngAnimation(png().writeInt(8).writeUtf8("acTL").writeInt(10)))
        assertFalse(hasApngAnimation(png().writeInt(-1).writeUtf8("tEXt")))
        assertFalse(hasApngAnimation(png().chunk("acTL", Buffer().writeInt(0).writeInt(0))))
        assertFalse(hasApngAnimation(png().chunk("acTL", Buffer().writeInt(10))))
    }
}
