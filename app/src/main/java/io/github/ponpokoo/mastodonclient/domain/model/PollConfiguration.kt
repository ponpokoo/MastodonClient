package io.github.ponpokoo.mastodonclient.domain.model

/** Conservative defaults when an instance does not publish its poll limits. */
data class PollConfiguration(
    val maxOptions: Int = 4,
    val maxCharactersPerOption: Int = 50,
    val minExpiration: Long = 300,
    val maxExpiration: Long = 604_800,
    val confirmed: Boolean = false,
)

fun String.pollCharacterCount(): Int = codePointCount(0, length)

fun PollConfiguration.validationError(options: List<String>, duration: Long): String? = when {
    options.isEmpty() -> null
    options.size !in 2..maxOptions -> "選択肢は2〜${maxOptions}個にしてください"
    options.any(String::isBlank) -> "投票の選択肢を入力してください"
    options.any { it.trim().pollCharacterCount() > maxCharactersPerOption } -> "選択肢は${maxCharactersPerOption}文字以内にしてください"
    duration !in minExpiration..maxExpiration -> "投票期間をサーバーの制限内で選択してください"
    else -> null
}
