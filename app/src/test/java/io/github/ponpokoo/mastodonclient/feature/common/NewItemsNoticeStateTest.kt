package io.github.ponpokoo.mastodonclient.feature.common

import org.junit.Assert.*
import org.junit.Test

class NewItemsNoticeStateTest {
    @Test fun pendingNoticeFreezesItsCountAndIgnoresOtherResultsUntilArrival() {
        val state = NewItemsNoticeState()
        state.update(6, atTop = false)
        val pending = state.notice!!
        state.update(9, atTop = false, resultKey = 1, resultCount = 3)
        assertEquals(pending, state.notice)
        assertTrue(state.notice!!.actionable)
        assertNull(state.notice!!.dismissAfterMillis)

        state.update(9, atTop = true)
        val arrived = state.notice!!
        assertEquals(pending.message, arrived.message)
        assertFalse(arrived.actionable)
        assertEquals(600L, arrived.dismissAfterMillis)
        state.update(0, atTop = true, resultKey = 2, resultCount = 9)
        assertEquals(arrived, state.notice)
        state.dismiss(pending.id)
        assertEquals(arrived, state.notice)
        state.dismiss(arrived.id)
        assertNull(state.notice)
        state.update(0, atTop = true, resultKey = 2, resultCount = 9)
        assertNull(state.notice)
    }

    @Test fun atTopResultExpiresWithoutReappearingAndOldTimerCannotDismissAnotherResult() {
        val state = NewItemsNoticeState()
        state.update(0, true, resultKey = 1, resultCount = 2)
        val first = state.notice!!
        assertFalse(first.actionable)
        assertEquals(1_800L, first.dismissAfterMillis)
        state.update(0, true, resultKey = 2, resultCount = 3)
        val second = state.notice!!
        state.dismiss(first.id)
        assertEquals(second, state.notice)
        state.dismiss(second.id)
        state.update(0, true, resultKey = 2, resultCount = 3)
        assertNull(state.notice)
    }

    @Test fun refreshAtTopBecomesAPassiveResultAndEmptyRefreshDoesNotCreateNotice() {
        val state = NewItemsNoticeState()
        state.update(0, true)
        assertNull(state.notice)
        state.update(4, true)
        val result = state.notice!!
        assertFalse(result.actionable)
        assertEquals(1_800L, result.dismissAfterMillis)
        state.update(0, true)
        assertEquals(result, state.notice)
    }

    @Test fun restoredWindowKeepsItsPromptThroughNewArrivalsAndLatestRequestUntilViewportArrives() {
        val state = NewItemsNoticeState()
        state.update(0, false, isRestored = true)
        val restored = state.notice!!
        state.update(7, false, resultKey = 1, resultCount = 7, isRestored = true)
        assertEquals(restored, state.notice)
        state.update(0, false) // latest request succeeded, layout has not reached the new rows yet
        assertEquals(restored, state.notice)
        state.update(0, true)
        assertEquals(restored.message, state.notice!!.message)
        assertEquals(600L, state.notice!!.dismissAfterMillis)
    }

    @Test fun notificationReceiptIsPassiveAndReadingOrRepeatedIdsDoNotRestartOrReviveIt() {
        val state = NewItemsNoticeState()
        state.updateNotificationCount(setOf("a", "b"))
        val receipt = state.notice!!
        assertFalse(receipt.actionable)
        assertEquals(1_800L, receipt.dismissAfterMillis)
        state.updateNotificationCount(setOf("b"))
        assertEquals(receipt, state.notice)
        state.dismiss(receipt.id)
        state.updateNotificationCount(setOf("b"))
        assertNull(state.notice)
        state.updateNotificationCount(setOf("b", "c"))
        assertNotNull(state.notice)
        assertTrue(state.notice!!.id > receipt.id)
        assertEquals("新着2件", state.notice!!.message)
        state.updateNotificationCount(emptySet())
        assertNotNull(state.notice)
    }

    @Test fun removingAllPendingRowsClearsNoticeAndNewContextStartsEmpty() {
        val state = NewItemsNoticeState()
        state.update(3, false)
        state.update(0, false)
        assertNull(state.notice)
        state.update(2, false)
        assertTrue(state.notice!!.message.contains("2件"))
        assertNull(NewItemsNoticeState().notice)
    }
}
