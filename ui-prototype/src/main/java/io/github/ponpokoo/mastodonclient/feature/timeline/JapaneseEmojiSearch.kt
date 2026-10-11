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
)

internal fun normalizeEmojiSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .map { char -> if (char in 'ァ'..'ヶ') char - ('ァ' - 'ぁ') else char }
    .joinToString("")

internal fun romajiToHiragana(text: String): String = Regex("[a-z]+").replace(text.lowercase(Locale.ROOT)) { match ->
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
                val pair = (3 downTo 1).firstNotNullOfOrNull { length ->
                    source.substring(index, (index + length).coerceAtMost(source.length))
                        .takeIf { it.length == length && it in kanaByRomaji }
                        ?.let { length to kanaByRomaji.getValue(it) }
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
    val needle = normalizeEmojiSearch(query.trim().trim(':'))
    return needle.isEmpty() || needle in customEmojiSearchKey(emoji)
}

internal fun customEmojiSearchKey(emoji: CustomEmoji): String {
    val shortcode = normalizeEmojiSearch(emoji.shortcode)
    val category = normalizeEmojiSearch(emoji.category.orEmpty())
    return "$shortcode $category ${romajiToHiragana(shortcode)}"
}
