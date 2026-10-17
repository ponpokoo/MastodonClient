package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test fun lAndRRowsAreAdditionalReadingsForKanaAndAlphabetQueries() {
        listOf("ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ").forEach { (r, kana) ->
            val l = "l" + r.drop(1)
            listOf(r, l).forEach { shortcode ->
                val key = customEmojiSearchKey(CustomEmoji(shortcode, "", ""))
                listOf(kana, r, l).forEach { query -> assertTrue("$shortcode / $query", key.matches(emojiSearchQuery(query))) }
            }
        }
        listOf("remon", "lemon").forEach { shortcode ->
            val emoji = CustomEmoji(shortcode, "", "")
            listOf("れもん", "レモン", "remon", "lemon", ":ＬＥＭＯＮ:", "ﾚﾓﾝ").forEach { query ->
                assertTrue("$shortcode / $query", customEmojiMatchesQuery(emoji, query))
            }
            assertEquals(shortcode, emoji.shortcode)
        }
    }

    @Test fun existingRomajiVariantsMatchInEitherInputDirection() {
        listOf("shi" to "si", "chi" to "ti", "tsu" to "tu", "fu" to "hu",
            "sha" to "sya", "chu" to "tyu", "jo" to "jyo").forEach { (first, second) ->
            assertTrue(customEmojiMatchesQuery(CustomEmoji(first, "", ""), second))
            assertTrue(customEmojiMatchesQuery(CustomEmoji(second, "", ""), first))
        }
        listOf("kitte" to "きって", "konnichiha" to "こんにちは", "shinbun" to "しんぶん",
            "nyanko" to "にゃんこ", "ryokou" to "りょこう").forEach { (shortcode, kana) ->
            assertTrue(customEmojiMatchesQuery(CustomEmoji(shortcode, "", ""), kana))
        }
    }

    @Test fun directEnglishCategoryAndSmallKanaMatchingArePreserved() {
        val emoji = CustomEmoji("Blue_flower@remote.test", "", "", category = "ＦＬＯＷＥＲＳ 花")
        listOf("blue", "FLOWER", "@remote.test", "flowers", "花", "", " : ").forEach { query ->
            assertTrue(query, customEmojiMatchesQuery(emoji, query))
        }
        assertFalse(customEmojiMatchesQuery(emoji, "あおいはな"))
        listOf("la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
            "ltu" to "っ", "ltsu" to "っ", "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
            "xtsu" to "っ", "xya" to "ゃ").forEach { (shortcode, kana) ->
            assertTrue("$shortcode / $kana", customEmojiMatchesQuery(CustomEmoji(shortcode, "", ""), kana))
        }
        assertFalse(customEmojiMatchesQuery(CustomEmoji("lya", "", ""), "りゃ"))
        assertFalse(customEmojiMatchesQuery(CustomEmoji("ltu", "", ""), "るつ"))
    }
}
