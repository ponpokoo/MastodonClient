package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.PushSyncSource
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PushSyncRepositoryTest {
    private val session = AccountSession("one", "https://example.test", "me", "me", "Me", "", "secret", "read push")
    private val keys = WebPushKeyGenerator().generate()
    private val registrationId = "r".repeat(43)
    private fun notification(id: String) = TimelineNotification(id, "mention", "2026-10-07T00:00:00Z",
        StatusAuthor("actor", "Actor", "actor", ""), null)
    private inner class Environment {
        var accounts = listOf(session)
        val records = mutableMapOf(session.sessionId to StoredPushRegistration(PushRegistrationGuard.binding(session),
            "https://relay.test", registrationId, "management", keys, "https://relay.test/push/delivery",
            PushRegistrationState.ACTIVE, syncInitialized = true, syncSinceId = "opaque-start"))
        val store = object : PushRegistrationStore {
            override suspend fun read(sessionId: String) = records[sessionId]
            override suspend fun write(sessionId: String, record: StoredPushRegistration) { records[sessionId] = record }
            override suspend fun remove(sessionId: String) { records.remove(sessionId) }
        }
        val calls = mutableListOf<Pair<String?, String?>>()
        val scheduled = mutableListOf<Pair<String, String>>()
        val shown = mutableListOf<String>()
        var clock = 1_000_000L
        var fetch: suspend (String?, String?) -> List<TimelineNotification> = { _, max ->
            if (max == null) listOf(notification("newest"), notification("older")) else emptyList()
        }
        var onShow: suspend (String) -> Unit = {}
        var onSchedule: suspend () -> Unit = {}
        fun repository() = DefaultPushSyncRepository({ accounts }, store, PushSyncSource { account, since, max ->
            assertEquals(session.instanceUrl, account.instanceUrl)
            calls += since to max; fetch(since, max)
        }, SyncedNotificationPresenter { _, notification, current ->
            onShow(notification.id)
            if (current() && notification.id !in shown) shown += notification.id
        }, { sessionId, id -> onSchedule(); scheduled += sessionId to id }, { clock })
        fun record() = records.getValue(session.sessionId)
    }

    @Test fun burstCoalescesApiCallsAndShortPagesAreFullyReadWithOpaqueLowerBound() = runTest {
        val env = Environment(); val repo = env.repository()
        repeat(5) { assertTrue(repo.requestRegistration(registrationId)) }
        repeat(5) { repo.sync(session.sessionId, registrationId) }
        assertEquals(listOf("opaque-start" to null, "opaque-start" to "older"), env.calls)
        assertEquals(listOf("older", "newest"), env.shown)
        assertEquals("newest", env.record().syncSinceId)
        assertEquals(env.record().syncRequested, env.record().syncCompleted)
        assertTrue(env.record().syncDeliveredIds.isEmpty())
    }

    @Test fun failedLaterPageLeavesCheckpointAndNoPartialNotificationsThenRecreatedRepositoryRecovers() = runTest {
        val env = Environment(); val repo = env.repository()
        repo.requestRegistration(registrationId)
        env.fetch = { _, max -> if (max == null) listOf(notification("newest")) else throw IOException() }
        try { repo.sync(session.sessionId, registrationId); fail() } catch (_: IOException) { }
        assertEquals("opaque-start", env.record().syncSinceId)
        assertEquals(0L, env.record().syncCompleted)
        assertTrue(env.shown.isEmpty())
        env.fetch = { _, max -> if (max == null) listOf(notification("newest")) else emptyList() }
        env.repository().sync(session.sessionId, registrationId)
        assertEquals(listOf("newest"), env.shown)
        assertEquals("newest", env.record().syncSinceId)
    }

    @Test fun aPushArrivingDuringFetchKeepsItsGenerationForAnotherRun() = runTest {
        val env = Environment(); val repo = env.repository()
        repo.requestRegistration(registrationId)
        var requested = false
        env.fetch = { _, max ->
            if (!requested) { requested = true; repo.requestRegistration(registrationId) }
            if (max == null) listOf(notification("newest")) else emptyList()
        }
        repo.sync(session.sessionId, registrationId)
        assertEquals(2L, env.record().syncRequested); assertEquals(1L, env.record().syncCompleted)
        env.fetch = { since, max ->
            assertEquals("newest", since)
            if (max == null) listOf(notification("later")) else emptyList()
        }
        repo.sync(session.sessionId, registrationId)
        assertEquals(listOf("newest", "later"), env.shown)
        assertEquals("later", env.record().syncSinceId)
        assertEquals(2L, env.record().syncCompleted)
    }

    @Test fun logoutReauthorizationDisableAndRegistrationReplacementRejectLateResponses() = runTest {
        for (change in 0..4) {
            val env = Environment(); val repo = env.repository(); repo.requestRegistration(registrationId)
            env.fetch = { _, _ ->
                when (change) {
                    0 -> env.accounts = emptyList()
                    1 -> env.accounts = listOf(session.copy(accessToken = "new-secret"))
                    2 -> env.records[session.sessionId] = env.record().copy(state = PushRegistrationState.REMOVING)
                    3 -> env.records[session.sessionId] = env.record().copy(registrationId = "x".repeat(43))
                    4 -> env.accounts = listOf(session.copy(scopes = "push"))
                }
                listOf(notification("late"))
            }
            repo.sync(session.sessionId, registrationId)
            assertTrue(env.shown.isEmpty())
            assertEquals("opaque-start", env.record().syncSinceId)
        }
    }

    @Test fun recoveryIsAccountScopedThrottledAndForceBypassesInterval() = runTest {
        val env = Environment(); val repo = env.repository()
        env.records[session.sessionId] = env.record().copy(syncCheckedAt = env.clock)
        repo.recover(null, false); assertTrue(env.scheduled.isEmpty())
        repo.recover("other", true); assertTrue(env.scheduled.isEmpty())
        repo.recover(session.sessionId, true); assertEquals(1, env.scheduled.size)
        repo.sync(session.sessionId, registrationId)
        repo.recover(null, false); assertEquals(1, env.scheduled.size)
        env.clock += 15 * 60 * 1000L
        repo.recover(null, false); assertEquals(2, env.scheduled.size)
    }

    @Test fun disabledAndUnknownAccountsNeverScheduleOrCallApi() = runTest {
        val env = Environment(); val repo = env.repository()
        assertFalse(repo.requestRegistration("unknown"))
        env.records[session.sessionId] = env.record().copy(state = PushRegistrationState.REMOVING)
        assertFalse(repo.requestRegistration(registrationId))
        repo.recover(null, true); repo.sync(session.sessionId, registrationId)
        assertTrue(env.calls.isEmpty()); assertTrue(env.scheduled.isEmpty())
    }

    @Test fun enqueueFailurePersistsPendingRequestForForegroundRecovery() = runTest {
        val env = Environment(); val repo = env.repository(); env.onSchedule = { throw IOException() }
        try { repo.requestRegistration(registrationId); fail() } catch (_: IOException) { }
        assertTrue(env.record().syncRequested > env.record().syncCompleted)
        env.onSchedule = {}
        repo.recover(null, false); repo.sync(session.sessionId, registrationId)
        assertEquals(listOf("older", "newest"), env.shown)
    }

    @Test fun stalledPaginationAndCancellationDoNotAdvanceOrDisplay() = runTest {
        for (cancel in listOf(false, true)) {
            val env = Environment(); val repo = env.repository(); repo.requestRegistration(registrationId)
            env.fetch = { _, _ -> if (cancel) throw CancellationException() else listOf(notification("same")) }
            try { repo.sync(session.sessionId, registrationId); fail() }
            catch (_: CancellationException) { assertTrue(cancel) }
            catch (_: IllegalStateException) { assertFalse(cancel) }
            assertEquals("opaque-start", env.record().syncSinceId); assertTrue(env.shown.isEmpty())
        }
    }

    @Test fun silentLegacyBootstrapDoesNotReplayHistoryButSyncRequiredBootstrapsVisiblePage() = runTest {
        for (push in listOf(false, true)) {
            val env = Environment(); val repo = env.repository()
            env.records[session.sessionId] = env.record().copy(syncInitialized = false, syncSinceId = null)
            if (push) repo.requestRegistration(registrationId) else repo.recover(null, false)
            repo.sync(session.sessionId, registrationId)
            assertEquals(1, env.calls.size)
            assertEquals(if (push) listOf("older", "newest") else emptyList<String>(), env.shown)
            assertEquals("newest", env.record().syncSinceId); assertTrue(env.record().syncInitialized)
        }
    }

    @Test fun savedInFlightReceiptsPreventRepeatedPresentationAfterProcessRestart() = runTest {
        val env = Environment(); val repo = env.repository(); repo.requestRegistration(registrationId)
        env.onShow = { if (it == "newest") throw IOException() }
        try { repo.sync(session.sessionId, registrationId); fail() } catch (_: IOException) { }
        assertEquals(listOf("older"), env.record().syncDeliveredIds)
        assertEquals("opaque-start", env.record().syncSinceId)
        var olderCalls = 0
        env.onShow = { if (it == "older") olderCalls++ }
        env.repository().sync(session.sessionId, registrationId)
        assertEquals(0, olderCalls)
        assertEquals(listOf("older", "newest"), env.shown)
    }

    @Test fun newNotificationsOnInitiallyEmptyAccountAndSubscriptionTypesKeepRawCursor() = runTest {
        val env = Environment(); val repo = env.repository()
        env.records[session.sessionId] = env.record().copy(syncSinceId = null, notificationTypes = listOf("mention"))
        env.fetch = { _, max -> if (max == null) listOf(notification("filtered").copy(type = "favourite"), notification("mention")) else emptyList() }
        repo.requestRegistration(registrationId); repo.sync(session.sessionId, registrationId)
        assertEquals(listOf("mention"), env.shown)
        assertEquals("filtered", env.record().syncSinceId)
        assertEquals(listOf(null to null, null to "mention"), env.calls)
    }

    @Test fun aPushDuringSilentLegacyBootstrapIsNotLost() = runTest {
        val env = Environment(); val repo = env.repository()
        env.records[session.sessionId] = env.record().copy(syncInitialized = false, syncSinceId = null)
        repo.recover(null, false)
        env.fetch = { _, _ -> repo.requestRegistration(registrationId); listOf(notification("newest")) }
        repo.sync(session.sessionId, registrationId)
        assertEquals(listOf("newest"), env.shown)
        assertEquals(1L, env.record().syncCompleted); assertEquals(2L, env.record().syncRequested)
        env.fetch = { _, _ -> emptyList() }
        repo.sync(session.sessionId, registrationId)
        assertEquals(2L, env.record().syncCompleted)
    }

    @Test fun tokenUpdateInProgressRetriesInsteadOfSilentlyFinishingPendingSync() = runTest {
        val env = Environment(); val repo = env.repository(); repo.requestRegistration(registrationId)
        env.fetch = { _, _ ->
            env.records[session.sessionId] = env.record().copy(state = PushRegistrationState.REGISTERING)
            listOf(notification("newest"))
        }
        try { repo.sync(session.sessionId, registrationId); fail() } catch (_: IOException) { }
        assertEquals("opaque-start", env.record().syncSinceId); assertTrue(env.shown.isEmpty())
        env.records[session.sessionId] = env.record().copy(state = PushRegistrationState.ACTIVE)
        env.fetch = { _, max -> if (max == null) listOf(notification("newest")) else emptyList() }
        repo.sync(session.sessionId, registrationId)
        assertEquals(listOf("newest"), env.shown)
        assertEquals(env.record().syncRequested, env.record().syncCompleted)
    }
}
