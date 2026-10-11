package io.github.ponpokoo.mastodonclient.feature.timeline

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun relativeTime(
    value: String,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = runCatching {
    val instant = Instant.parse(value)
    val duration = Duration.between(instant, now).coerceAtLeast(Duration.ZERO)
    val oneYearAgo = now.atZone(zone).minusYears(1).toInstant()
    when {
        duration.seconds < 60 -> "今"
        duration.toMinutes() < 60 -> "${duration.toMinutes()}分前"
        duration.toHours() < 24 -> "${duration.toHours()}時間前"
        duration.toDays() < 7 -> "${duration.toDays()}日前"
        else -> DateTimeFormatter.ofPattern(
            if (instant <= oneYearAgo) "yyyy年M月d日" else "M月d日",
        ).withZone(zone).format(instant)
    }
}.getOrDefault("")
