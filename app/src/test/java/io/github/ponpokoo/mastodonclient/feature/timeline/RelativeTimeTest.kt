package io.github.ponpokoo.mastodonclient.feature.timeline

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val now = Instant.parse("2026-10-01T03:00:00Z")

    @Test fun includesYearFromTheOneYearBoundary() {
        assertEquals("10月1日", relativeTime("2025-10-01T03:00:01Z", now, zone))
        assertEquals("2025年10月1日", relativeTime("2025-10-01T03:00:00Z", now, zone))
        assertEquals("2024年9月30日", relativeTime("2024-09-30T03:00:00Z", now, zone))
    }

    @Test fun usesCalendarYearsAcrossLeapYearsAndLocalDates() {
        val leapYearNow = Instant.parse("2024-03-01T00:00:00Z")
        assertEquals("3月1日", relativeTime("2023-03-01T00:00:01Z", leapYearNow, zone))
        assertEquals("2023年3月1日", relativeTime("2023-03-01T00:00:00Z", leapYearNow, zone))
        assertEquals("2025年10月1日", relativeTime("2025-09-30T16:00:00Z", now, zone))
    }

    @Test fun preservesRecentFutureAndInvalidTimestampHandling() {
        assertEquals("今", relativeTime(now.plusSeconds(60).toString(), now, zone))
        assertEquals("5分前", relativeTime(now.minusSeconds(300).toString(), now, zone))
        assertEquals("2時間前", relativeTime(now.minusSeconds(7200).toString(), now, zone))
        assertEquals("3日前", relativeTime(now.minusSeconds(259200).toString(), now, zone))
        assertEquals("9月24日", relativeTime(now.minusSeconds(604800).toString(), now, zone))
        assertEquals("", relativeTime("invalid", now, zone))
    }
}
