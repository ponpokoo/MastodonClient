package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.core.security.MemoryAuthPreferences
import io.github.ponpokoo.mastodonclient.core.security.memoryAuthStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAccountSettingsRepositoryTest {
    @Test fun filtersProbeV2OnlyAndDistinguishUnsupportedFailureAndSuccessfulEmpty() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val local = UserPreferencesStore(MemoryAuthPreferences())
            fun repository() = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            val repository = repository()
            for (code in listOf(404, 405, 501, 401, 403, 429, 500, 502, 503)) {
                server.enqueue(MockResponse().setResponseCode(code))
                assertTrue(repository.syncServerSettings(account, setOf(ServerSetting.Filters)).isFailure)
                val state = repository.serverSettings.value.getValue(account.sessionId)
                assertNull(state.filters)
                assertNull(state.fetch[ServerSetting.Filters]?.fetchedAt)
                assertFalse(state.fetch.getValue(ServerSetting.Filters).loading)
                assertEquals(code in setOf(404, 405, 501), state.fetch.getValue(ServerSetting.Filters).unsupported)
                assertEquals(code !in setOf(404, 405, 501), state.fetch.getValue(ServerSetting.Filters).error != null)
                assertEquals("/api/v2/filters", server.takeRequest().path)
            }
            server.enqueue(MockResponse().setBody("""[{"id":"opaque-id","title":"saved","context":["home"],"filter_action":"hide","future_field":true}]"""))
            repository.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            val before = repository.serverSettings.value.getValue(account.sessionId)
            assertEquals("opaque-id", before.filters!!.single().id)
            assertNotNull(before.fetch.getValue(ServerSetting.Filters).fetchedAt)
            val matched = testStatus().copy(filtered = listOf(StatusFilterResult(before.filters.single())))
            assertTrue(matched.filterPresentation("home", current = before.filters).hidden)
            for (response in listOf(MockResponse().setResponseCode(401), MockResponse().setResponseCode(503),
                MockResponse().setBody("not-json"), MockResponse().setBody("null"), MockResponse().setResponseCode(204),
                MockResponse().setBody(" ".repeat(10000) + "[]").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))) {
                server.enqueue(response)
                assertTrue(repository.syncServerSettings(account, setOf(ServerSetting.Filters)).isFailure)
                val failed = repository.serverSettings.value.getValue(account.sessionId)
                assertEquals(before.filters, failed.filters)
                assertEquals(before.fetch.getValue(ServerSetting.Filters).fetchedAt, failed.fetch.getValue(ServerSetting.Filters).fetchedAt)
                assertFalse(failed.fetch.getValue(ServerSetting.Filters).unsupported)
                assertNotNull(failed.fetch.getValue(ServerSetting.Filters).error)
                assertTrue(matched.filterPresentation("home", current = failed.filters).hidden)
                val restarted = repository(); restarted.prepareServerSettings(account)
                assertEquals(before.filters, restarted.serverSettings.value.getValue(account.sessionId).filters)
            }
            server.enqueue(MockResponse().setBody("[]"))
            repository.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            val empty = repository.serverSettings.value.getValue(account.sessionId)
            assertEquals(emptyList<ServerFilter>(), empty.filters)
            assertFalse(empty.fetch.getValue(ServerSetting.Filters).unsupported)
            assertNull(empty.fetch.getValue(ServerSetting.Filters).error)
            assertFalse(matched.filterPresentation("home", current = empty.filters).hidden)
            assertEquals(17, server.requestCount)
            repeat(8) { assertEquals("/api/v2/filters", server.takeRequest().path) }
        }
    }

    @Test fun serverSyncAndAccountActivationNeverReplaceLocalReadingChoices() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val other = secondAccount.copy(instanceUrl = account.instanceUrl)
            val data = MemoryAuthPreferences()
            val local = UserPreferencesStore(data)
            local.setMediaDisplay(account.sessionId, MediaDisplay.HideAll)
            local.setExpandSpoilers(account.sessionId, true)
            local.setDefaultSensitive(account.sessionId, true)
            local.setMediaDisplay(other.sessionId, MediaDisplay.ShowAll)
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            for (session in listOf(account, other, account)) {
                repository.activateServerSettings(session)
                server.enqueue(MockResponse().setBody("""{"reading:expand:media":"default","reading:expand:spoilers":false,"posting:default:visibility":"private","posting:default:sensitive":${session.sessionId == other.sessionId}}"""))
                repository.syncServerSettings(session, setOf(ServerSetting.Preferences)).getOrThrow()
                assertEquals("GET", server.takeRequest().method)
                assertEquals("private", repository.serverSettings.value.getValue(session.sessionId).defaults?.visibility)
                val restored = UserPreferencesStore(data).preferences.first()
                assertEquals(ReadingPreferences(MediaDisplay.HideAll, true), restored.readingForAccount(account))
                assertEquals(ReadingPreferences(MediaDisplay.ShowAll, false), restored.readingForAccount(other))
                assertTrue(restored.forAccount(account.sessionId).defaultSensitive)
                assertFalse(restored.forAccount(other.sessionId).defaultSensitive)
            }
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(repository.syncServerSettings(account, setOf(ServerSetting.Preferences)).isFailure)
            assertEquals(ReadingPreferences(MediaDisplay.HideAll, true), local.preferences.first().readingForAccount(account))
            assertEquals(3 + 1, server.requestCount)
        }
    }

    @Test fun postingVisibilityPatchUpdatesOnlyConfirmedDefaultAndPreservesCacheOnFailure() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val local = UserPreferencesStore(MemoryAuthPreferences())
            local.saveServerSettings(account.sessionId, ServerAccountSettings(account.instanceUrl, account.accountId,
                defaults = PostingDefaults("unlisted", "followers", "ja", true, true)))
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            server.enqueue(MockResponse().setBody("""{"id":"${account.accountId}","username":"me","acct":"me","source":{"privacy":"private"}}"""))
            repository.savePostingVisibility(account, "private").getOrThrow()
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/v1/accounts/update_credentials", request.path)
            assertEquals("source%5Bprivacy%5D=private", request.body.readUtf8())
            val expected = PostingDefaults("private", "followers", "ja", true, true)
            assertEquals(expected, repository.serverSettings.value.getValue(account.sessionId).defaults)
            assertEquals(expected, local.preferences.first().serverSettings.getValue(account.sessionId).defaults)
            server.enqueue(MockResponse().setResponseCode(403))
            val failure = repository.savePostingVisibility(account, "public")
            assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
            assertEquals(expected, repository.serverSettings.value.getValue(account.sessionId).defaults)
            server.enqueue(MockResponse().setBody("""{"id":"${account.accountId}","username":"me","acct":"me"}"""))
            assertTrue(repository.savePostingVisibility(account, "public").isFailure)
            assertEquals(expected, repository.serverSettings.value.getValue(account.sessionId).defaults)
            assertTrue(repository.savePostingVisibility(account, "direct").isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun olderPreferencesResponseCannotOverwriteSavedPostingVisibility() = runTest {
        MockWebServer().use { server ->
            val gate = java.util.concurrent.CountDownLatch(1)
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/api/v1/preferences") {
                    gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setBody("""{"posting:default:visibility":"public"}""")
                } else MockResponse().setBody("""{"id":"${account.accountId}","username":"me","acct":"me","source":{"privacy":"private"}}""")
            }
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            val older = async { repository.syncServerSettings(account, setOf(ServerSetting.Preferences)) }
            runCurrent()
            try {
                assertEquals("/api/v1/preferences", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path)
                repository.savePostingVisibility(account, "private").getOrThrow()
            } finally { gate.countDown() }
            older.await().getOrThrow()
            assertEquals("private", repository.serverSettings.value.getValue(account.sessionId).defaults!!.visibility)
        }
    }

    private val entry = ModerationAccount(testStatus().author, AccountRelationship(muting = true, mutingNotifications = false))
    @Test fun keepsSuccessfulCacheOnFailureAndUsesExplicitNullLanguage() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val local = UserPreferencesStore(MemoryAuthPreferences())
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> Result.success(ModerationAccountsPage(emptyList(), null)) }, { _, _, _ -> })
            server.enqueue(MockResponse().setBody("""{"posting:default:visibility":"unlisted","posting:default:quote_policy":"followers","posting:default:language":"ja"}"""))
            repository.syncServerSettings(account, setOf(ServerSetting.Preferences)).getOrThrow()
            server.enqueue(MockResponse().setBody("""{"posting:default:language":null}"""))
            repository.syncServerSettings(account, setOf(ServerSetting.Preferences)).getOrThrow()
            val before = repository.serverSettings.value.getValue(account.sessionId).defaults!!
            assertEquals("unlisted", before.visibility); assertNull(before.language); assertTrue(before.languageKnown)
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(repository.syncServerSettings(account, setOf(ServerSetting.Preferences)).isFailure)
            assertEquals(before, repository.serverSettings.value.getValue(account.sessionId).defaults)
            assertNotNull(repository.serverSettings.value.getValue(account.sessionId).fetch[ServerSetting.Preferences]?.error)
            val restored = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> error("cache must not fetch") }, { _, _, _ -> })
            restored.prepareServerSettings(account)
            assertEquals(before, restored.serverSettings.value.getValue(account.sessionId).defaults)
        }
    }
    @Test fun paginationFailureDoesNotReplaceLastCompleteMuteSnapshot() = runTest {
        var fail = false
        val cursors = mutableListOf<String?>()
        val local = UserPreferencesStore(MemoryAuthPreferences())
        val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null, { _, _, cursor ->
            cursors += cursor
            if (fail && cursor != null) Result.failure(IllegalStateException("offline"))
            else Result.success(ModerationAccountsPage(if (cursor == null) listOf(entry) else listOf(entry.copy(account = entry.account.copy(id = "other"))), if (cursor == null) "opaque-cursor" else null))
        }, { _, _, _ -> })
        repository.syncServerSettings(testAccount, setOf(ServerSetting.Mutes)).getOrThrow()
        val before = repository.serverSettings.value.getValue(testAccount.sessionId).mutes
        fail = true
        assertTrue(repository.syncServerSettings(testAccount, setOf(ServerSetting.Mutes)).isFailure)
        assertEquals(before, repository.serverSettings.value.getValue(testAccount.sessionId).mutes)
        assertEquals(listOf(null, "opaque-cursor", null, "opaque-cursor"), cursors)
    }
    @Test fun operationSuccessWinsOverInFlightListAndPreservesOtherKind() = runTest {
        val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
            { _, _, _ -> gate.await() }, { _, _, _ -> })
        repository.prepareServerSettings(testAccount)
        val sync = async { repository.syncServerSettings(testAccount, setOf(ServerSetting.Mutes)) }
        runCurrent()
        repository.recordRelationship(testAccount, entry.account, AccountRelationship(muting = true, blocking = true))
        gate.complete(Result.success(ModerationAccountsPage(emptyList(), null)))
        sync.await()
        val saved = repository.serverSettings.value.getValue(testAccount.sessionId)
        assertEquals(entry.account.id, saved.mutes!!.single().account.id)
        assertTrue(saved.blocks!!.single().relationship.blocking)
    }
    @Test fun cancelledOldActivationCannotCommitEvenIfSourceIgnoresCancellation() = runTest {
        val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
            { _, _, _ -> withContext(NonCancellable) { gate.await() } }, { _, _, _ -> })
        val old = async { repository.syncServerSettings(testAccount, setOf(ServerSetting.Mutes)) }
        runCurrent(); repository.activateServerSettings(testAccount)
        gate.complete(Result.success(ModerationAccountsPage(listOf(entry), null)))
        runCatching { old.await() }
        assertNull(repository.serverSettings.value.getValue(testAccount.sessionId).mutes)
    }
    @Test fun registrationGuardRejectsLateReplyAfterRemoval() = runTest {
        val auth = memoryAuthStore(); auth.saveSession(testAccount)
        val guard = AccountDisplaySynchronizer(auth)
        val local = UserPreferencesStore(MemoryAuthPreferences())
        val gate = CompletableDeferred<Result<ModerationAccountsPage>>()
        val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, guard,
            { _, _, _ -> gate.await() }, { _, _, _ -> })
        val request = async { repository.syncServerSettings(testAccount, setOf(ServerSetting.Mutes)) }
        runCurrent(); guard.changeRegistration { auth.removeSession(testAccount.sessionId) }
        gate.complete(Result.success(ModerationAccountsPage(listOf(entry), null))); request.await()
        assertNull(local.preferences.first().serverSettings[testAccount.sessionId])
    }
    @Test fun coalescesRequestsAndDistinguishesUnsupportedFromEmptyFilters() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            server.enqueue(MockResponse().setBody("[]").setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            val first = async { repository.syncServerSettings(account, setOf(ServerSetting.Filters)) }
            val second = async { repository.syncServerSettings(account, setOf(ServerSetting.Filters)) }
            awaitAll(first, second).forEach { it.getOrThrow() }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(repository.syncServerSettings(account, setOf(ServerSetting.Filters)).isFailure)
            assertEquals(emptyList<ServerFilter>(), repository.serverSettings.value.getValue(account.sessionId).filters)
            assertTrue(repository.serverSettings.value.getValue(account.sessionId).fetch.getValue(ServerSetting.Filters).unsupported)
        }
    }
    @Test fun filterEditsPreserveIdsWholeWordAndLeaveExpiryAndStatusRulesUntouched() {
        val original = ServerFilter("filter", "old", setOf("notifications"), "2030-01-01T00:00:00Z", "warn",
            listOf(FilterKeyword("keep", "word", true), FilterKeyword("remove", "other")), listOf(FilterStatus("rule", "post")))
        val fields = filterFields(original, FilterEdit("new", setOf("notifications"), "hide", listOf(FilterKeyword("keep", "changed", true))))
        assertEquals("keep", fields["keywords_attributes[0][id]"])
        assertEquals("true", fields["keywords_attributes[0][whole_word]"])
        assertEquals("remove", fields["keywords_attributes[1][id]"])
        assertEquals("true", fields["keywords_attributes[1][_destroy]"])
        assertFalse(fields.containsKey("expires_in")); assertTrue(fields.keys.none { it.startsWith("statuses") })
    }
    @Test fun filterCrudUsesConfirmedResponsesAndRetainsCacheOnPermissionFailure() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            server.enqueue(MockResponse().setBody("[]"))
            repository.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            val edit = FilterEdit("warning", setOf("notifications", "home"), "warn", listOf(FilterKeyword(keyword = "word", wholeWord = true)))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"opaque-id","title":"warning","context":["notifications"],"filter_action":"warn","keywords":[{"id":"keyword-id","keyword":"word","whole_word":true}]}"""))
            val created = repository.saveServerFilter(account, null, edit).getOrThrow()
            assertEquals(listOf(created), repository.serverSettings.value.getValue(account.sessionId).filters)
            server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(repository.saveServerFilter(account, created, edit.copy(action = "hide")).isFailure)
            assertEquals(listOf(created), repository.serverSettings.value.getValue(account.sessionId).filters)
            server.enqueue(MockResponse().setBody("""{"id":"opaque-id","title":"warning","context":["notifications"],"filter_action":"hide"}"""))
            val updated = repository.saveServerFilter(account, created, edit.copy(action = "hide", keywords = created.keywords)).getOrThrow()
            assertEquals("hide", updated.action)
            server.enqueue(MockResponse().setResponseCode(204))
            repository.deleteServerFilter(account, created.id).getOrThrow()
            assertTrue(repository.serverSettings.value.getValue(account.sessionId).filters!!.isEmpty())
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("GET", "POST", "PUT", "PUT", "DELETE"), requests.map { it.method })
            assertEquals("/api/v2/filters/opaque-id", requests.last().path)
            for (index in listOf(1, 3)) {
                val fields = java.net.URLDecoder.decode(requests[index].body.readUtf8(), "UTF-8")
                assertEquals(setOf("context[]=notifications", "context[]=home"), fields.split('&').filter { it.startsWith("context") }.toSet())
                assertTrue(fields.contains("keywords_attributes[0][whole_word]=true"))
            }
        }
    }
    @Test fun undecodableCreateIsRememberedAcrossRestartAndNotSentAgainUntilConfirmed() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val local = UserPreferencesStore(MemoryAuthPreferences())
            fun repository() = DefaultServerAccountSettingsRepository(ApiClientFactory(), local, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            val first = repository()
            val edit = FilterEdit("warning", setOf("home"), "warn", emptyList())
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(first.saveServerFilter(account, null, edit).isFailure)
            assertEquals(edit, first.serverSettings.value.getValue(account.sessionId).pendingCreation)
            val restored = repository(); restored.prepareServerSettings(account)
            assertTrue(restored.saveServerFilter(account, null, edit).isFailure)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("[]"))
            restored.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            assertNull(restored.serverSettings.value.getValue(account.sessionId).pendingCreation)
            server.enqueue(MockResponse().setBody("""{"id":"new","title":"warning","context":["home"]}"""))
            assertEquals("new", restored.saveServerFilter(account, null, edit).getOrThrow().id)
        }
    }
    @Test fun failedDisplayInOneScreenRemainsPendingAndOldRefreshCannotAcknowledgeNewRules() = runTest {
        MockWebServer().use { server ->
            val account = testAccount.copy(instanceUrl = server.url("/").toString())
            val repository = DefaultServerAccountSettingsRepository(ApiClientFactory(), null, backgroundScope, null,
                { _, _, _ -> error("unused") }, { _, _, _ -> })
            server.enqueue(MockResponse().setBody("[]"))
            repository.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            repository.updateFilterDisplay(account, "timeline", emptyList(), null)
            repository.updateFilterDisplay(account, "notifications", emptyList(), false)
            repository.updateFilterDisplay(account, "timeline", emptyList(), true)
            assertTrue(repository.serverSettings.value.getValue(account.sessionId).displayPending)
            repository.updateFilterDisplay(account, "notifications", emptyList(), true)
            assertFalse(repository.serverSettings.value.getValue(account.sessionId).displayPending)
            server.enqueue(MockResponse().setBody("""[{"id":"new","title":"new","context":["home"]}]"""))
            repository.syncServerSettings(account, setOf(ServerSetting.Filters)).getOrThrow()
            repository.updateFilterDisplay(account, "timeline", emptyList(), true)
            assertTrue(repository.serverSettings.value.getValue(account.sessionId).displayPending)
        }
    }
}
