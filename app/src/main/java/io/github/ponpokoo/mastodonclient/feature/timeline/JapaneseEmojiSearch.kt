package io.github.ponpokoo.mastodonclient.feature.timeline

import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import java.text.Normalizer
import java.util.Locale

private val kanaByRomaji = mapOf(
    "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
    "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
    "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
    "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "te" to "て", "to" to "と",
    "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
    "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
    "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
    "ya" to "や", "yu" to "ゆ", "yo" to "よ",
    "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
    "wa" to "わ", "wo" to "を", "n" to "ん",
    "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
    "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
    "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
    "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
    "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
    "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
    "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
    "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
    "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
    "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
    "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
    "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
    "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
    "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
    "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
    "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
    "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
    "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
    "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
    "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
    "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
    "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
    "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
    "xtu" to "っ", "ltu" to "っ", "xtsu" to "っ", "ltsu" to "っ",
)

private val lRowReadings = mapOf("la" to "ら", "li" to "り", "lu" to "る", "le" to "れ", "lo" to "ろ")
private val latinRun = Regex("[a-z]+")

internal fun normalizeEmojiSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .map { char -> if (char in 'ァ'..'ヶ') char - ('ァ' - 'ぁ') else char }
    .joinToString("")

internal fun romajiToHiragana(text: String, lRow: Boolean = false): String = latinRun.replace(text.lowercase(Locale.ROOT)) { match ->
    val source = match.value
    buildString {
        var index = 0
        while (index < source.length) {
            val current = source[index]
            if (current == 'n' && (index == source.lastIndex ||
                    source.getOrNull(index + 1) == 'n' ||
                    (source.getOrNull(index + 1)?.let { it !in "aiueoy" } ?: true))) {
                append('ん')
                index++
            } else if (index + 1 < source.length && current == source[index + 1] &&
                current !in "aiueon") {
                append('っ')
                index++
            } else {
                val pair = (4 downTo 1).firstNotNullOfOrNull { length ->
                    source.substring(index, (index + length).coerceAtMost(source.length))
                        .takeIf { it.length == length && it in kanaByRomaji }
                        ?.let { length to ((if (lRow) lRowReadings[it] else null) ?: kanaByRomaji.getValue(it)) }
                }
                if (pair == null) {
                    append(current)
                    index++
                } else {
                    append(pair.second)
                    index += pair.first
                }
            }
        }
    }
}

internal fun customEmojiMatchesQuery(emoji: CustomEmoji, query: String): Boolean {
    return customEmojiSearchKey(emoji).matches(emojiSearchQuery(query))
}

/** Keep direct matching independent of ambiguous readings (English and IME small kana). */
internal data class EmojiSearchQuery(val direct: String, val readings: Set<String>)

internal fun emojiSearchQuery(query: String): EmojiSearchQuery {
    val normalized = normalizeEmojiSearch(query.trim().trim(':'))
    return EmojiSearchQuery(normalized, emojiReadings(normalized))
}

private fun emojiReadings(normalized: String): Set<String> = setOf(
    romajiToHiragana(normalized), romajiToHiragana(normalized, lRow = true),
)

internal data class CustomEmojiSearchKey(val shortcode: String, val category: String, val readings: Set<String>) {
    fun matches(query: EmojiSearchQuery): Boolean = query.direct.isEmpty() ||
        query.direct in shortcode || query.direct in category ||
        query.readings.any { needle -> readings.any { needle in it } }
}

internal fun customEmojiSearchKey(emoji: CustomEmoji): CustomEmojiSearchKey {
    val shortcode = normalizeEmojiSearch(emoji.shortcode)
    val category = normalizeEmojiSearch(emoji.category.orEmpty())
    return CustomEmojiSearchKey(shortcode, category, emojiReadings(shortcode))
}
