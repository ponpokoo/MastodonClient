package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.PollOption
import io.github.ponpokoo.mastodonclient.domain.model.StatusPoll
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import org.junit.Assert.*
import org.junit.Test

class StatusActionUpdateTest {
    @Test fun storedBatchAppliesSameStatusFieldsInOrderAndKeepsBoostIdentity() {
        val original = testStatus().copy(favourited = false, reblogged = false)
        val boosted = original.copy(timelineId = "boost", boostedBy = original.author, createdAt = "later")
        val unrelated = original.copy(statusId = "unrelated")
        fun stored(field: StatusActionField, status: io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus) =
            BrowsingSession.Change.StatusUpdated(status, field, StatusUpdateKind.Stored)
        val batch = BrowsingSession.Change.StoredStatusUpdates(listOf(
            stored(StatusActionField.Favourite, original.copy(favourited = true, favouritesCount = 2)),
            stored(StatusActionField.Reblog, original.copy(reblogged = true, boostsCount = 3)),
            stored(StatusActionField.Favourite, original.copy(favourited = false, favouritesCount = 1)),
        ))
        val expected = boosted.copy(favouritesCount = 1, reblogged = true, boostsCount = 3)

        assertEquals(expected, batch.applyTo(boosted))
        assertEquals(listOf(expected, unrelated), listOf(boosted, unrelated).applyHomeChange(batch))
        assertSame(unrelated, batch.applyTo(unrelated))
    }

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
