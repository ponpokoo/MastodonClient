package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import java.time.Instant
import kotlin.math.roundToLong

internal fun pollDurationLabel(seconds: Long, maxExpiration: Long? = null): String = when {
    seconds == maxExpiration && seconds >= 86_400L && seconds % 86_400L != 0L ->
        "約${(seconds / 86_400.0).roundToLong()}日"
    seconds % 86_400L == 0L -> "${seconds / 86_400L}日"
    seconds % 3_600L == 0L -> "${seconds / 3_600L}時間"
    seconds % 60L == 0L -> "${seconds / 60L}分"
    else -> "${seconds}秒"
}

internal fun StatusPoll.expirationInstant(): Instant? = expiresAt?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

internal fun StatusPoll.hasEnded(now: Instant): Boolean = expired || expirationInstant()?.let { !it.isAfter(now) } == true

internal fun StatusPoll.remainingLabel(now: Instant): String? {
    val end = expirationInstant() ?: return null
    if (hasEnded(now)) return null
    val seconds = java.time.Duration.between(now, end).seconds
    return when {
        seconds >= 86_400 -> "残り${seconds / 86_400}日"
        seconds >= 3_600 -> "残り${seconds / 3_600}時間"
        seconds >= 60 -> "残り${seconds / 60}分"
        else -> "まもなく終了"
    }
}

/** A missing voter count must not silently change the meaning of multiple-choice percentages. */
internal fun StatusPoll.resultFraction(optionVotes: Long?): Float? {
    if (optionVotes == null) return null
    val denominator = if (multiple) votersCount ?: return null else votesCount
    return if (denominator <= 0) 0f else (optionVotes.toDouble() / denominator).toFloat().coerceIn(0f, 1f)
}
