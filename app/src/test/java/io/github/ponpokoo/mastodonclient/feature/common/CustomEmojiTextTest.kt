package io.github.ponpokoo.mastodonclient.feature.common

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CustomEmojiTextTest {
    @Test
    fun replacesOnlyKnownShortcodesAtTheirOriginalPositions() {
        val source = buildAnnotatedString {
            append("A :twitch: and :unknown: ")
            pushStringAnnotation("url", "https://example.com")
            append("link")
            pop()
            append(" :youtube:")
        }
        val expected = buildAnnotatedString {
            append("A ")
            appendInlineContent("custom-emoji:twitch", ":twitch:")
            append(" and :unknown: ")
            pushStringAnnotation("url", "https://example.com")
            append("link")
            pop()
            append(" ")
            appendInlineContent("custom-emoji:youtube", ":youtube:")
        }

        assertEquals(
            expected,
            replaceCustomEmojiShortcodes(
                source,
                mapOf("twitch" to "https://example.com/twitch.png", "youtube" to "https://example.com/youtube.png"),
            ),
        )
    }

    @Test
    fun preservesTextWhenNoEmojiIsProvided() {
        val source = AnnotatedString(":twitch: hello")
        assertSame(source, replaceCustomEmojiShortcodes(source, emptyMap()))
    }
}
