package io.github.ponpokoo.mastodonclient.domain.session

import io.github.ponpokoo.mastodonclient.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HomeTimelineWindowTest {
    private fun status(id: String) = TimelineStatus(id, "post-$id", "", StatusAuthor("a", "A", "a", ""),
        null, "", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private fun page(vararg ids: String) = TimelinePage(ids.map(::status), ids.lastOrNull(), ids.isEmpty(), HomeTimelineIndex(ids.toList()))

    @Test fun disconnectedRefreshKeepsOldWindowAndFillsOnlyOnePagePerMerge() {
        val old = page("old", "older")
        val refreshed = old.mergeHomePage(page("latest", "new"))
        assertEquals(listOf("latest", "new", "old", "older"), refreshed.homeIndex().ids)
        assertEquals(HomeTimelineGap("old", "new"), refreshed.homeIndex().gaps.single())
        assertEquals("older", refreshed.nextMaxId)
        val partial = refreshed.fillHomeGap(refreshed.homeIndex().gaps.single(), page("middle"))
        assertEquals(listOf("latest", "new", "middle", "old", "older"), partial.homeIndex().ids)
        assertEquals("middle", partial.homeIndex().gaps.single().maxId)
        val joined = partial.fillHomeGap(partial.homeIndex().gaps.single(), page("near-old", "old"))
        assertTrue(joined.homeIndex().gaps.isEmpty())
        assertEquals(listOf("latest", "new", "middle", "near-old", "old", "older"), joined.homeIndex().ids)
    }

    @Test fun laterRefreshPreservesEarlierGapAndOverlapCanCloseMultipleGaps() {
        val first = page("old").mergeHomePage(page("new"))
        val second = first.mergeHomePage(page("latest"))
        assertEquals(2, second.homeIndex().gaps.size)
        val joined = second.mergeHomePage(page("latest", "new", "old"))
        assertTrue(joined.homeIndex().gaps.isEmpty())
        assertEquals(3, joined.statuses.size)
    }

    @Test fun hiddenPageAdvancesCursorAndHiddenOverlapClosesGap() {
        val refreshed = page("hidden-old", "old").mergeHomePage(page("new"))
        val hidden = page("hidden-middle").copy(statuses = emptyList())
        val partial = refreshed.fillHomeGap(refreshed.homeIndex().gaps.single(), hidden)
        assertEquals("hidden-middle", partial.homeIndex().gaps.single().maxId)
        assertFalse(partial.endReached)
        val joined = partial.fillHomeGap(partial.homeIndex().gaps.single(), page("hidden-old").copy(statuses = emptyList()))
        assertTrue(joined.homeIndex().gaps.isEmpty())
    }

    @Test fun emptyOrNonAdvancingResponseDoesNotClaimContinuityAndPartialDoesNotMutate() {
        val refreshed = page("old").mergeHomePage(page("new"))
        val gap = refreshed.homeIndex().gaps.single()
        assertTrue(refreshed.fillHomeGap(gap, page()).homeIndex().gaps.single().unavailable)
        assertTrue(refreshed.fillHomeGap(gap, page("new")).homeIndex().gaps.single().unavailable)
        val ignoredCursor = page("old").mergeHomePage(page("latest", "new"))
        val ignored = ignoredCursor.fillHomeGap(ignoredCursor.homeIndex().gaps.single(), page("latest"))
        assertEquals(ignoredCursor.homeIndex().ids, ignored.homeIndex().ids)
        assertTrue(ignored.homeIndex().gaps.single().unavailable)
        assertEquals(refreshed, refreshed.fillHomeGap(gap, page().copy(homeIndex = HomeTimelineIndex(emptyList(), partial = true))))
    }

    @Test fun deletedBoundaryRemainsUsableAndBoostRowsStayDistinct() {
        val refreshed = page("old", "older").mergeHomePage(page("new"))
        val deleted = refreshed.applyHomeWindowChange(BrowsingSession.Change.StatusDeleted("post-new"))
        assertFalse(deleted.statuses.any { it.timelineId == "new" })
        val joined = deleted.fillHomeGap(deleted.homeIndex().gaps.single(), page("boost", "old").let {
            it.copy(statuses = it.statuses.map { row -> row.copy(statusId = "same-post") })
        })
        assertTrue(joined.homeIndex().gaps.isEmpty())
        assertEquals(2, joined.statuses.count { it.statusId == "same-post" })
    }

    @Test fun cacheBudgetProtectsReaderAndMarksEvictedMiddleAsGap() {
        val original = page(*(0..599).map { "opaque-$it" }.toTypedArray())
        val bounded = original.boundHomeWindow("opaque-550")
        assertTrue(bounded.homeIndex().ids.size <= 500)
        assertTrue("opaque-550" in bounded.homeIndex().ids)
        assertEquals("opaque-0", bounded.homeIndex().ids.first())
        assertEquals(HomeTimelineGap("opaque-530", "opaque-39"), bounded.homeIndex().gaps.single())
        assertFalse(original.boundHomeWindow(null).endReached)
    }

    @Test fun streamingOverlapAloneDoesNotCloseExistingGap() {
        val disconnected = page("old").applyHomeWindowChange(BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(status("live"))))
        val refreshed = disconnected.mergeHomePage(page("new", "live"))
        assertEquals("old", refreshed.homeIndex().gaps.single().beforeId)
    }

    @Test fun enablingWindowModeRetainsAnInvisibleFooterCursor() {
        val legacy = TimelinePage(listOf(status("visible")), "hidden-footer", false)
        val appended = legacy.mergeHomePage(page("older"), "hidden-footer")
        assertEquals(listOf("visible", "older"), appended.statuses.map { it.timelineId })
        assertEquals("older", appended.nextMaxId)
        assertTrue(appended.homeIndex().gaps.isEmpty())
    }
}
