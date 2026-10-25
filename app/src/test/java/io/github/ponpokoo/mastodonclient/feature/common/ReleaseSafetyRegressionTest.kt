package io.github.ponpokoo.mastodonclient.feature.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.feature.compose.ComposePostViewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Regressions for submission, account isolation and connection policy. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseSafetyRegressionTest : ScreenViewModelTestBase() {
    private class Memory : DataStore<Preferences> {
        var fail = false
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (fail) throw IOException("synthetic disk failure")
            return transform(data.value).also { data.value = it }
        }
    }
    private val auth = object : AuthRepository {
        override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
        override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
        override suspend fun restoreSession() = testAccount
        override suspend fun getSessions() = listOf(testAccount, secondAccount)
        override suspend fun logout() = Unit
    }
    private fun compose(repo: ScreenRepositoryFake, store: UserPreferencesStore = UserPreferencesStore(Memory())) = own(
        ComposePostViewModel(null, null, repo, auth, store, {}, object : DraftMediaRepository {
            override suspend fun importMedia(sessionId: String, uris: List<String>) = Result.success(MediaImportResult())
        })
    )

    @Test fun differentDraftRotatesFailedRequestKey() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<String, String>>()
        val repo = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                requests += request.text to idempotencyKey
                return Result.failure(IOException("synthetic lost response"))
            }
        }
        val vm = compose(repo); runCurrent()
        vm.onTextChanged("first"); vm.post(); runCurrent()
        vm.restoreDraft(ComposeDraft("other", testAccount.sessionId, text = "second")); runCurrent()
        vm.post(); runCurrent()
        assertEquals(listOf("first", "second"), requests.map { it.first })
        assertNotEquals(requests[0].second, requests[1].second)
    }

    @Test fun mentionResponseFromOldAccountIsDiscarded() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<StatusAuthor>>()
        val oldAuthor = testStatus().author.copy(id = "old-only", accountName = "old@one.example")
        val repo = object : ScreenRepositoryFake() {
            override suspend fun getAccountList(session: AccountSession, accountId: String, followers: Boolean, maxId: String?) = withContext(NonCancellable) { Result.success(gate.await()) }
        }
        val vm = compose(repo); runCurrent()
        vm.loadMentionCandidates(); runCurrent()
        vm.switchPostingAccount(secondAccount.sessionId); runCurrent()
        assertEquals(secondAccount.sessionId, vm.uiState.value.selectedSession?.sessionId)
        vm.switchPostingAccount(testAccount.sessionId); runCurrent()
        gate.complete(listOf(oldAuthor)); runCurrent()
        assertTrue(vm.uiState.value.mentionCandidates.isEmpty())
        vm.loadMentionCandidates(); runCurrent()
        assertEquals(listOf(oldAuthor), vm.uiState.value.mentionCandidates)
    }

    @Test
    fun postSuccessSurvivesCleanupFailure() = runTest(dispatcher) {
        var accepted = 0
        val repo = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                accepted++
                return Result.success(testStatus())
            }
        }
        val memory = Memory()
        val store = UserPreferencesStore(memory)
        val vm = compose(repo, store); runCurrent()
        vm.restoreDraft(ComposeDraft("saved", testAccount.sessionId, text = "accepted")); runCurrent()
        store.retainComposeBuffer(ComposeDraft("${testAccount.sessionId}:new:", testAccount.sessionId, text = "accepted"))
        memory.fail = true
        vm.post(); runCurrent()
        assertEquals(1, accepted)
        assertFalse(vm.uiState.value.isPosting)
        assertTrue(vm.uiState.value.posted)
        assertFalse(vm.uiState.value.canClose)
        vm.post(); runCurrent()
        assertEquals(1, accepted)
        assertNull(store.getComposeBuffer("${testAccount.sessionId}:new:"))
    }

    @Test fun wifiOnlyStreamDoesNotReconnectAfterWifiDisappears() = runTest(dispatcher) {
        var wifi = true
        var connections = 0
        val disconnect = CompletableDeferred<Unit>()
        val repo = object : ScreenRepositoryFake() {
            override fun observeUserStream(session: AccountSession) = flow<TimelineStreamEvent> {
                connections++
                if (connections == 1) {
                    disconnect.await()
                    throw IOException("synthetic network handover")
                }
                awaitCancellation()
            }
        }
        val preferences = UserPreferencesStore(Memory())
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(streaming = StreamingPolicy.WifiOnly))
        own(MainSessionViewModel(auth, repo, preferences, networkIsWifi = { wifi }))
        runCurrent()
        assertEquals(1, connections)
        wifi = false
        disconnect.complete(Unit)
        runCurrent(); advanceTimeBy(2_001); runCurrent()
        assertEquals(1, connections)
    }

    @Test fun retriesKeepKeyButChangedPayloadAndAccountRotateIt() = runTest(dispatcher) {
        val keys = mutableListOf<String>()
        val repo = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                keys += idempotencyKey
                return Result.failure(IOException("synthetic lost response"))
            }
        }
        val vm = compose(repo); runCurrent()
        vm.onTextChanged("first"); vm.post(); runCurrent()
        vm.post(); runCurrent()
        assertEquals(keys[0], keys[1])
        vm.onTextChanged("changed"); vm.post(); runCurrent()
        assertNotEquals(keys[1], keys[2])
        vm.setVisibility(PostVisibility.Unlisted); vm.post(); runCurrent()
        assertNotEquals(keys[2], keys[3])
        vm.switchPostingAccount(secondAccount.sessionId); runCurrent()
        vm.onTextChanged("changed"); vm.post(); runCurrent()
        assertNotEquals(keys[3], keys[4])
    }

    @Test fun networkChangesStopWifiOnlyStreamAndResumeWithoutForegroundChange() = runTest(dispatcher) {
        var wifi = true
        var connections = 0
        var active = 0
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val repo = object : ScreenRepositoryFake() {
            override fun observeNetworkChanges() = changes
            override fun observeUserStream(session: AccountSession) = flow<TimelineStreamEvent> {
                connections++; active++
                try { awaitCancellation() } finally { active-- }
            }
        }
        val preferences = UserPreferencesStore(Memory())
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(streaming = StreamingPolicy.WifiOnly))
        own(MainSessionViewModel(auth, repo, preferences, networkIsWifi = { wifi })); runCurrent()
        assertEquals(1, active)
        wifi = false; changes.emit(Unit); runCurrent()
        assertEquals(0, active)
        wifi = true; changes.emit(Unit); runCurrent()
        assertEquals(1, active)
        assertEquals(2, connections)
        changes.emit(Unit); runCurrent()
        assertEquals(2, connections)
        preferences.setAccountPreferences(testAccount.sessionId, AccountPreferences(streaming = StreamingPolicy.Off)); runCurrent()
        changes.emit(Unit); runCurrent()
        assertEquals(0, active)
        assertEquals(2, connections)
    }
}
