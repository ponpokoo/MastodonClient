package io.github.ponpokoo.mastodonclient.feature.status

import io.github.ponpokoo.mastodonclient.domain.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PollPresentationTest {
    @Test fun serverMaximumUsesApproximateDaysWithoutChangingOrdinaryDurations() {
        assertEquals("約30日", pollDurationLabel(2_629_746L, 2_629_746L))
        assertEquals("7日", pollDurationLabel(604_800L, 604_800L))
        assertEquals("1時間", pollDurationLabel(3_600L, 3_600L))
        assertEquals("30分", pollDurationLabel(1_800L, 2_629_746L))
        assertEquals("2629746秒", pollDurationLabel(2_629_746L))
    }

    private val poll = StatusPoll("poll", "2026-11-02T09:00:00Z", false, true, 18, 10, false,
        emptySet(), listOf(PollOption("apple", 8)))

    @Test fun multipleChoiceUsesVotersAndDoesNotInventPercentagesWhenMissing() {
        assertEquals(0.8f, poll.resultFraction(8)!!, 0.001f)
        assertEquals(0.6f, poll.resultFraction(6)!!, 0.001f)
        assertNull(poll.copy(votersCount = null).resultFraction(8))
        assertNull(poll.resultFraction(null))
        assertEquals(0f, poll.copy(votersCount = 0).resultFraction(0)!!, 0f)
        assertEquals(1f, poll.resultFraction(20)!!, 0f)
    }

    @Test fun singleChoiceUsesVotesAndDeadlineIncludesTheExactEndInstant() {
        assertEquals(0.5f, poll.copy(multiple = false, votesCount = 16).resultFraction(8)!!, 0f)
        assertFalse(poll.hasEnded(Instant.parse("2026-11-02T08:59:59Z")))
        assertTrue(poll.hasEnded(Instant.parse("2026-11-02T09:00:00Z")))
        assertEquals("残り1時間", poll.remainingLabel(Instant.parse("2026-11-02T08:00:00Z")))
        assertEquals("まもなく終了", poll.remainingLabel(Instant.parse("2026-11-02T08:59:59Z")))
        assertNull(poll.remainingLabel(Instant.parse("2026-11-02T09:00:00Z")))
        assertTrue(poll.copy(expired = true).hasEnded(Instant.EPOCH))
        assertFalse(poll.copy(expiresAt = null).hasEnded(Instant.now()))
        assertFalse(poll.copy(expiresAt = "invalid").hasEnded(Instant.now()))
    }
}
