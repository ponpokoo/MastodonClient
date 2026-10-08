package io.github.ponpokoo.mastodonclient.notification

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationDeliveryCoordinatorTest {
    @Test fun accountRemovalSerializesWithDeliveryAndPreventsLateHistoryAndMarkers() = runTest {
        val ledger = mutableMapOf<String, List<String>>()
        val coordinator = NotificationDeliveryCoordinator({ ledger[it].orEmpty() }, { id, ids -> ledger[id] = ids })
        var registered = true
        val visible = mutableSetOf<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val delivering = async {
            coordinator.deliver("a", "one", { gate.await(); registered }) { visible += "a"; true }
        }
        runCurrent()
        val removing = async { coordinator.removeAccount { registered = false; ledger.remove("a"); visible.remove("a") } }
        gate.complete(Unit)
        delivering.await(); removing.await()
        assertTrue(visible.isEmpty()); assertFalse(ledger.containsKey("a"))
        assertFalse(coordinator.deliver("a", "late", { registered }) { error("Deleted account must not notify") })
        coordinator.acknowledge("a", setOf("late"), { registered }) { error("Must not recreate ledger") }
        coordinator.updateIfCurrent({ registered }) { error("Must not recreate polling marker") }
        assertTrue(coordinator.deliver("b", "one", { true }) { visible += "b"; true })
        assertEquals(setOf("b"), visible)
    }
    @Test fun readNotificationIsNotDeliveredLateAndOtherNotificationsAreUnaffected() = runTest {
        val ledger = mutableMapOf<String, List<String>>()
        val coordinator = NotificationDeliveryCoordinator({ ledger[it].orEmpty() }, { session, ids -> ledger[session] = ids })
        var dismissed = false
        coordinator.acknowledge("a", setOf("read")) { dismissed = true }
        assertTrue(dismissed)
        assertFalse(coordinator.deliver("a", "read", { true }) { error("Read notification must not reappear") })
        assertTrue(coordinator.deliver("a", "unread", { true }) { true })
        assertTrue(coordinator.deliver("b", "read", { true }) { true })
    }
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
