package io.github.ponpokoo.mastodonclient.notification

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationDeliveryCoordinatorTest {
    @Test fun streamingPushAndPollingShareOneDeliveryAcrossCoordinatorInstances() = runTest {
        val ledger = mutableMapOf<String, List<String>>()
        fun coordinator() = NotificationDeliveryCoordinator({ ledger[it].orEmpty() }, { session, ids -> ledger[session] = ids })
        var posted = 0
        val results = (1..3).map { async { coordinator().deliver("session", "notification", { true }) { posted++; true } } }.awaitAll()
        assertEquals(1, results.count { it }); assertEquals(1, posted)
        assertFalse(coordinator().deliver("session", "notification", { true }) { posted++; true })
        assertTrue(coordinator().deliver("another-session", "notification", { true }) { posted++; true })
        assertEquals(2, posted)
    }
    @Test fun disabledOrFailedNotificationDoesNotPoisonRetryHistory() = runTest {
        val ledger = mutableMapOf<String, List<String>>()
        val coordinator = NotificationDeliveryCoordinator({ ledger[it].orEmpty() }, { session, ids -> ledger[session] = ids })
        assertFalse(coordinator.deliver("a", "id", { false }) { error("Must not post") })
        assertFalse(coordinator.deliver("a", "id", { true }) { false })
        assertTrue(coordinator.deliver("a", "id", { true }) { true })
    }
}
