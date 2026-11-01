package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.PollOption
import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import org.junit.Assert.*
import org.junit.Test

class StatusActionUpdateTest {
    @Test fun votingUpdatesOriginalAndBoostedCopiesWithoutChangingTheirListIdentity() {
        val poll = StatusPoll("poll", null, false, false, 0, 0, false, emptySet(),
            listOf(PollOption("first", null), PollOption("second", null)))
        val original = testStatus().copy(poll = poll)
        val boosted = original.copy(timelineId = "boost-wrapper", boostedBy = original.author.copy(id = "booster"))
        val result = original.copy(poll = poll.copy(voted = true, ownVotes = setOf(1), votesCount = 1,
            votersCount = 1, options = listOf(PollOption("first", 0), PollOption("second", 1))))

        for (displayed in listOf(original, boosted)) {
            val updated = displayed.withUpdatedActions(result)
            assertEquals(result.poll, updated.poll)
            assertEquals(displayed.timelineId, updated.timelineId)
            assertEquals(displayed.boostedBy, updated.boostedBy)
        }
        assertEquals(original, original.withUpdatedActions(result.copy(statusId = "other")))
    }
}
