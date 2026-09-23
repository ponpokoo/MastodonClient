package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushKeys
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.PushMessageSource
import io.github.ponpokoo.mastodonclient.data.remote.dto.RelayMessageDto
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PushMessageRepositoryTest {
    private val json = RelayMessageDto.json
    private val fixtures = javaClass.getResourceAsStream("/push/encrypted.json")!!.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    private val session = AccountSession("one", "https://instance.example", "a", "user", "User", "", "token")
    private val keys = json.decodeFromJsonElement<WebPushKeys>(fixtures.getValue("keys"))
    private fun envelope(name: String = "standard") = json.decodeFromJsonElement<RelayMessageDto>(fixtures.getValue(name))
    private fun inline(name: String = "standard") = fixtures.getValue(name).jsonObject.mapValues { it.value.jsonPrimitive.content } + ("transport" to "inline")
    private fun fetchMessage(name: String = "standard") = inline(name).filterKeys { it in setOf("version", "registrationId", "messageId") } + ("transport" to "fetch")
    private inner class Environment {
        var accounts = listOf(session)
        var stored: StoredPushRegistration? = StoredPushRegistration(PushRegistrationGuard.binding(session), "https://relay.example/", "r".repeat(43), "s".repeat(43), keys,
            endpoint = "https://relay.example/push/delivery", state = PushRegistrationState.ACTIVE)
        val displayed = mutableListOf<Pair<AccountSession, PushNotification>>()
        var fetches = 0
        var onFetch: suspend () -> RelayMessageDto? = { envelope() }
        val store = object : PushRegistrationStore {
            override suspend fun read(sessionId: String) = stored.takeIf { sessionId == "one" }
            override suspend fun write(sessionId: String, record: StoredPushRegistration) { stored = record }
            override suspend fun remove(sessionId: String) { stored = null }
        }
        val repository = DefaultPushMessageRepository({ accounts }, store, PushMessageSource { relay, id, message, token ->
            fetches++
            assertEquals("https://relay.example/", relay)
            assertEquals("r".repeat(43), id); assertEquals("m".repeat(43), message); assertEquals("s".repeat(43), token)
            onFetch()
        }, PushNotificationPresenter { account, notification, current ->
            if (current()) displayed += account to notification
        })
    }

    @Test fun inlineStandardAndLegacyDisplayPlainTextAndPreserveOpaqueIds() = runTest {
        for (kind in listOf("standard", "legacy")) {
            val env = Environment()
            assertEquals(PushReceiveResult.PROCESSED, env.repository.receive(inline(kind)))
            assertEquals(0, env.fetches)
            val (account, notification) = env.displayed.single()
            assertEquals(session, account)
            assertEquals("123456789012345678901234567890", notification.id)
            assertEquals("future_type", notification.type)
            assertEquals("<b>そのままのテキスト</b>", notification.body)
        }
    }
    @Test fun encryptedFedibirdReactionReachesNotificationPresenter() = runTest {
        val env = Environment()
        assertEquals(PushReceiveResult.PROCESSED, env.repository.receive(inline("emojiReaction")))
        val notification = env.displayed.single().second
        assertEquals("emoji_reaction", notification.type)
        assertEquals("テストさんがリアクションしました", notification.title)
        assertEquals("🎉", notification.body)
    }

    @Test fun largeFetchUsesStoredRelayAndNoTransportProvidedUrl() = runTest {
        val env = Environment().apply { onFetch = { envelope("large") } }
        assertEquals(PushReceiveResult.PROCESSED, env.repository.receive(fetchMessage() + ("url" to "https://attacker.example/")))
        assertEquals(1, env.fetches)
        assertEquals("large-id", env.displayed.single().second.id)
        assertEquals(2048, env.displayed.single().second.body.length)
    }
    @Test fun inactiveOrUnrecognizedRegistrationNeverFetches() = runTest {
        for (state in listOf(PushRegistrationState.REMOVING, PushRegistrationState.REGISTERING)) {
            val env = Environment().apply { stored = stored!!.copy(state = state) }
            assertEquals(PushReceiveResult.IGNORED, env.repository.receive(fetchMessage()))
            assertEquals(0, env.fetches)
        }
        val env = Environment()
        assertEquals(PushReceiveResult.IGNORED, env.repository.receive(fetchMessage() + ("registrationId" to "z".repeat(43))))
        assertEquals(0, env.fetches)
    }
    @Test fun logoutReauthenticationAndDisableDuringFetchSuppressNotification() = runTest {
        for (change in listOf("logout", "credentials", "disable", "replace")) {
            val env = Environment()
            env.onFetch = {
                when (change) {
                    "logout" -> env.accounts = emptyList()
                    "credentials" -> env.accounts = listOf(session.copy(accessToken = "changed"))
                    "disable" -> env.stored = env.stored!!.copy(state = PushRegistrationState.REMOVING)
                    "replace" -> env.stored = env.stored!!.copy(registrationId = "z".repeat(43))
                }
                envelope()
            }
            assertEquals(PushReceiveResult.IGNORED, env.repository.receive(fetchMessage()))
            assertTrue(env.displayed.isEmpty())
        }
    }
    @Test fun fetchMustMatchRequestedIdentityAndExpiredPayloadIsIgnored() = runTest {
        val env = Environment()
        val valid = envelope()
        env.onFetch = { RelayMessageDto("1", "other", valid.messageId, valid.encoding, valid.headers, valid.body) }
        assertEquals(PushReceiveResult.REJECTED, env.repository.receive(fetchMessage()))
        env.onFetch = { null }
        assertEquals(PushReceiveResult.IGNORED, env.repository.receive(fetchMessage()))
        assertTrue(env.displayed.isEmpty())
    }
    @Test fun invalidFinalPaddingIsRejectedEvenWithValidAuthenticationTags() = runTest {
        val env = Environment()
        for (name in listOf("invalidStandardPadding", "invalidLegacyPadding")) {
            assertEquals(PushReceiveResult.REJECTED, env.repository.receive(inline(name)))
        }
        assertTrue(env.displayed.isEmpty())
    }
    @Test fun malformedEnvelopeIsRejectedWithoutFetching() = runTest {
        val env = Environment()
        for (data in listOf(inline() - "body", inline() + ("body" to "!bad"), inline() + ("version" to "2"),
            fetchMessage() + ("messageId" to "../secret"), inline() + ("body" to "a".repeat(100001)))) {
            assertEquals(PushReceiveResult.REJECTED, env.repository.receive(data))
        }
        assertEquals(0, env.fetches)
    }
    @Test fun networkFailureAndCancellationPropagateForCaller() = runTest {
        val env = Environment()
        env.onFetch = { throw IOException("Offline") }
        try { env.repository.receive(fetchMessage()); fail() } catch (_: IOException) { }
        env.onFetch = { throw CancellationException("Cancelled") }
        try { env.repository.receive(fetchMessage()); fail() } catch (_: CancellationException) { }
        assertTrue(env.displayed.isEmpty())
    }
    @Test fun currentlyViewedAccountDoesNotChangePushDestination() = runTest {
        val env = Environment().apply { accounts = listOf(session.copy(sessionId = "other", accountId = "b"), session) }
        assertEquals(PushReceiveResult.PROCESSED, env.repository.receive(inline()))
        assertEquals("one", env.displayed.single().first.sessionId)
    }
}
