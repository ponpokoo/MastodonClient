package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseEmojiSearchTest {
    private val greeting = CustomEmoji("ohayou", "https://example.test/emoji.png", "https://example.test/emoji.png")

    @Test fun japanesePrefixFindsRomanizedShortcode() {
        assertTrue(customEmojiMatchesQuery(greeting, "おは"))
        assertTrue(customEmojiMatchesQuery(greeting, "オハヨウ"))
        assertTrue(customEmojiMatchesQuery(greeting, "oha"))
        assertFalse(customEmojiMatchesQuery(greeting, "こんばんは"))
    }

    @Test fun commonRomajiSpellingsAndNasalNConvert() {
        assertTrue(customEmojiMatchesQuery(CustomEmoji("konnichiha", "", ""), "こんにちは"))
        assertTrue(customEmojiMatchesQuery(CustomEmoji("shiawase", "", ""), "しあわせ"))
        assertTrue(customEmojiMatchesQuery(CustomEmoji("si_awase", "", ""), "し"))
    }
}
