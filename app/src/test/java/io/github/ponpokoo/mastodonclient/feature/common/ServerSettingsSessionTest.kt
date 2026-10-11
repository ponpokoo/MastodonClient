package io.github.ponpokoo.mastodonclient.feature.common

import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSettingsSessionTest : ScreenViewModelTestBase() {
    @Test fun startupAndActualAccountSwitchSyncAllAndFilterEventsCoalesceWithoutLosingMidFetchEvent() = runTest(dispatcher) {
        val events = MutableSharedFlow<TimelineStreamEvent>()
        val calls = mutableListOf<Pair<String, Set<ServerSetting>>>()
        var gate: CompletableDeferred<Unit>? = null
        var current = testAccount
        val repository = object : ScreenRepositoryFake() {
            override fun observeUserStream(session: AccountSession) = events
            override suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting>): Result<Unit> {
                calls += session.sessionId to items
                if (items == setOf(ServerSetting.Filters)) gate?.await()
                return Result.success(Unit)
            }
        }
        val auth = object : AuthRepository {
            override suspend fun restoreSession() = current
            override suspend fun getSessions() = listOf(testAccount, secondAccount)
            override suspend fun switchSession(sessionId: String) = getSessions().first { it.sessionId == sessionId }.also { current = it }
            override suspend fun logout() = Unit
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(current)
        }
        val model = own(MainSessionViewModel(auth, repository)); runCurrent()
        assertEquals(listOf(testAccount.sessionId to ServerSetting.entries.toSet()), calls)
        gate = CompletableDeferred()
        events.emit(TimelineStreamEvent.Connected); events.emit(TimelineStreamEvent.FiltersChanged); events.emit(TimelineStreamEvent.FiltersChanged)
        advanceTimeBy(201); runCurrent()
        assertEquals(2, calls.size)
        events.emit(TimelineStreamEvent.FiltersChanged)
        gate!!.complete(Unit); gate = null
        advanceUntilIdle(); assertEquals(3, calls.size)
        assertTrue(calls.drop(1).all { it.second == setOf(ServerSetting.Filters) })
        model.switchAccount(secondAccount.sessionId); advanceUntilIdle()
        assertEquals(secondAccount.sessionId to ServerSetting.entries.toSet(), calls.last())
        val count = calls.size; model.switchAccount(secondAccount.sessionId); advanceUntilIdle(); assertEquals(count, calls.size)
    }
}
