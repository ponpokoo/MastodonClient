package io.github.ponpokoo.mastodonclient.feature.compose

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.ComposeDraft
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.DraftMediaRepository
import io.github.ponpokoo.mastodonclient.feature.common.*
import java.net.URLDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic requests and controlled storage waits; no real account or federation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeDraftLatencyInvestigationTest : ScreenViewModelTestBase() {
    private class MemoryPreferences : DataStore<Preferences> {
        var gate: CompletableDeferred<Unit>? = null
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            gate?.await()
            return transform(data.value).also { data.value = it }
        }
    }

    private fun model(repository: ScreenRepositoryFake, store: UserPreferencesStore) = own(
        ComposePostViewModel(null, null, repository, object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(testAccount)
            override suspend fun restoreSession() = testAccount
            override suspend fun getSessions() = listOf(testAccount)
            override suspend fun logout() = Unit
        }, store, {}, object : DraftMediaRepository {
            override suspend fun importMedia(uris: List<String>) = Result.success(MediaImportResult())
        }),
    )

    @Test fun ordinaryAndRestoredYouTubePostsHaveIdenticalWireBodiesAndHeadersExceptKey() = runTest(dispatcher) {
        MockWebServer().use { server ->
            val actualRepository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            val repository = object : ScreenRepositoryFake() {
                override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String) =
                    actualRepository.createStatus(useSession, request, idempotencyKey)
                private val useSession = session
            }
            // Contains CRLF, trailing whitespace, percent escapes, a literal plus and combining Unicode.
            val text = " 日本語e\u0301\r\nhttps://www.youtube.com/watch?v=abc_123&si=a%2Bb+x#t=10\nhttps://youtu.be/abc_123?t=12 \t"
            for (scenario in listOf("default", "configured", "poll")) {
                val poll = scenario == "poll"
                repeat(2) { server.enqueue(MockResponse().setBody(
                    """{"id":"post-$scenario-$it","created_at":"2026-10-03T00:00:00Z","account":{"id":"me","username":"me","acct":"me"},"content":"synthetic"}""")) }
                fun configure(vm: ComposePostViewModel) {
                    vm.onTextChanged(text)
                    if (scenario != "default") {
                        vm.setVisibility(PostVisibility.Unlisted)
                        vm.setLanguage("ja")
                        vm.onSpoilerChanged("注意")
                    }
                    if (poll) {
                        vm.enablePoll()
                        vm.setPollOption(0, " yes ")
                        vm.setPollOption(1, " no ")
                        vm.setPollMultiple(true)
                    }
                }
                val normal = model(repository, UserPreferencesStore(MemoryPreferences()))
                advanceUntilIdle()
                configure(normal)
                normal.post()
                val directResult = normal.uiState.first { it.posted || it.errorMessage != null }
                assertTrue(directResult.errorMessage, directResult.posted)

                val memory = MemoryPreferences()
                val store = UserPreferencesStore(memory)
                val saving = model(repository, store)
                advanceUntilIdle()
                configure(saving)
                saving.saveDraft()
                advanceUntilIdle()
                val encoded = memory.data.value[stringPreferencesKey("compose_drafts")]!!
                val draft = Json.decodeFromString<List<ComposeDraft>>(encoded).single()
                assertEquals(text, draft.text)
                assertFalse(encoded.contains("idempotency"))
                assertFalse(encoded.contains("scheduled"))
                val restored = model(repository, store)
                advanceUntilIdle()
                restored.restoreDraft(draft)
                advanceUntilIdle()
                assertEquals(text, restored.uiState.value.text)
                restored.post()
                val restoredResult = restored.uiState.first { it.posted || it.errorMessage != null }
                assertTrue(restoredResult.errorMessage, restoredResult.posted)

                val direct = server.takeRequest()
                val viaDraft = server.takeRequest()
                assertEquals("POST", direct.method)
                assertEquals("/api/v1/statuses", direct.path)
                assertEquals(direct.method, viaDraft.method)
                assertEquals(direct.path, viaDraft.path)
                val body = direct.body.readUtf8()
                assertEquals(body, viaDraft.body.readUtf8())
                val fields = body.split('&').associate { field ->
                    URLDecoder.decode(field.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(field.substringAfter('='), "UTF-8")
                }
                assertEquals(text, fields["status"])
                assertEquals("application/x-www-form-urlencoded", direct.getHeader("Content-Type"))
                assertFalse(fields.containsKey("scheduled_at"))
                assertFalse(fields.containsKey("media_ids[]"))
                assertFalse(fields.containsKey("in_reply_to_id"))
                assertFalse(fields.containsKey("quoted_status_id"))
                assertEquals(poll, fields.containsKey("poll[options][]"))
                if (scenario == "default") {
                    assertEquals(setOf("status", "sensitive", "visibility"), fields.keys)
                    assertEquals("false", fields["sensitive"])
                    assertEquals("public", fields["visibility"])
                } else {
                    assertEquals("ja", fields["language"])
                    assertEquals("注意", fields["spoiler_text"])
                    assertEquals("true", fields["sensitive"])
                    assertEquals("unlisted", fields["visibility"])
                }
                if (poll) {
                    assertEquals("86400", fields["poll[expires_in]"])
                    assertEquals("true", fields["poll[multiple]"])
                }
                assertEquals(direct.headers.toMultimap().filterKeys { !it.equals("Idempotency-Key", true) },
                    viaDraft.headers.toMultimap().filterKeys { !it.equals("Idempotency-Key", true) })
                assertNotEquals(direct.getHeader("Idempotency-Key"), viaDraft.getHeader("Idempotency-Key"))
                println("wire equality: scenario=$scenario; status round trip exact; omitted fields verified; headers equal except key")
            }
        }
    }

    @Test fun failedPostThenUnrelatedDraftRestoreReusesKeyInSameViewModel() = runTest(dispatcher) {
        val requests = mutableListOf<Pair<CreateStatusRequest, String>>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                requests += request to idempotencyKey
                return Result.failure(java.io.IOException("synthetic lost response"))
            }
        }
        val vm = model(repository, UserPreferencesStore(MemoryPreferences()))
        advanceUntilIdle()
        vm.onTextChanged("first https://youtu.be/first")
        vm.post()
        advanceUntilIdle()
        vm.restoreDraft(ComposeDraft("unrelated", testAccount.sessionId, text = "second https://youtu.be/second"))
        advanceUntilIdle()
        vm.post()
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertNotEquals(requests[0].first.text, requests[1].first.text)
        assertEquals(requests[0].second, requests[1].second)
        println("confirmed: changed post after failed request retains the same in-memory idempotency key")
    }

    @Test fun saveThenRestoreInSameViewModelRotatesKeyAfterFailedAttempt() = runTest(dispatcher) {
        val keys = mutableListOf<String>()
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                keys += idempotencyKey
                return Result.failure(java.io.IOException("synthetic lost response"))
            }
        }
        val store = UserPreferencesStore(MemoryPreferences())
        val vm = model(repository, store)
        advanceUntilIdle()
        vm.onTextChanged("https://youtu.be/example")
        vm.post()
        advanceUntilIdle()
        vm.saveDraft()
        advanceUntilIdle()
        vm.restoreDraft(store.drafts.first().single())
        advanceUntilIdle()
        vm.post()
        advanceUntilIdle()
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test fun restoredTextPostWaitsForStorageBeforeSubmissionAndAfterApiSuccess() = runTest(dispatcher) {
        var posts = 0
        val repository = object : ScreenRepositoryFake() {
            override suspend fun createStatus(session: AccountSession, request: CreateStatusRequest, idempotencyKey: String): Result<TimelineStatus> {
                posts++
                return Result.success(testStatus())
            }
        }
        val memory = MemoryPreferences()
        val store = UserPreferencesStore(memory)
        store.saveDraft(ComposeDraft("saved", testAccount.sessionId, text = "https://youtu.be/example"))
        val vm = model(repository, store)
        advanceUntilIdle()
        val restoreGate = CompletableDeferred<Unit>()
        memory.gate = restoreGate
        vm.restoreDraft(store.drafts.first().single())
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        vm.post()
        runCurrent()
        assertEquals(0, posts)
        restoreGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(store.drafts.first().isEmpty())
        val cleanupGate = CompletableDeferred<Unit>()
        memory.gate = cleanupGate
        vm.post()
        runCurrent()
        assertEquals(1, posts)
        assertTrue(vm.uiState.value.isPosting)
        assertFalse(vm.uiState.value.posted)
        cleanupGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.posted)
        println("confirmed: restore storage gates submit; redundant draft deletion gates UI completion after API success")
    }
}
