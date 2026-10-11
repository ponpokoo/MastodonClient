package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ModerationManagementRepositoryTest {
    @Test fun mutesAndBlocksUseLinkCursorAndMapRelationshipsAndOptionalExpiry() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            for (kind in ModerationListKind.entries) {
                val name = if (kind == ModerationListKind.Mutes) "mutes" else "blocks"
                server.enqueue(MockResponse().setHeader("Link", "<https://another.example/api/v1/$name?max_id=opaque%2Bcursor>; rel=\"next\"")
                    .setBody("""[{"id":"opaque-account","username":"target","acct":"target@remote.example","mute_expires_at":"2026-10-05T00:01:00Z","future":true}]"""))
                server.enqueue(MockResponse().setBody("""[{"id":"opaque-account","muting":true,"blocking":true,"muting_notifications":false}]"""))
                val page = repository.getModerationAccounts(session, kind, "internal-cursor").getOrThrow()
                assertEquals("opaque+cursor", page.nextMaxId)
                assertEquals("opaque-account", page.accounts.single().account.id)
                assertEquals(false, page.accounts.single().relationship.mutingNotifications)
                assertEquals("2026-10-05T00:01:00Z", page.accounts.single().muteExpiresAt)
                assertEquals("/api/v1/$name?max_id=internal-cursor&limit=40", server.takeRequest().path)
                assertEquals("/api/v1/accounts/relationships?id%5B%5D=opaque-account", server.takeRequest().path)
            }
            server.enqueue(MockResponse().setBody("[]"))
            assertTrue(repository.getModerationAccounts(session, ModerationListKind.Mutes).getOrThrow().accounts.isEmpty())
            assertEquals(1, server.requestCount - 4)
            server.enqueue(MockResponse().setResponseCode(403))
            assertTrue(repository.getModerationAccounts(session, ModerationListKind.Blocks).isFailure)
        }
    }
    @Test fun undoMuteSendsPreviousNotificationsAndRemainingDuration() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"id":"opaque","muting":true,"blocking":true,"muting_notifications":false}"""))
            val relation = repository.restoreMute(session, "opaque", false, 59).getOrThrow()
            assertTrue(relation.muting); assertTrue(relation.blocking)
            val request = server.takeRequest()
            assertEquals("/api/v1/accounts/opaque/mute", request.path)
            assertEquals("notifications=false&duration=59", request.body.readUtf8())
        }
    }
    @Test fun undoKeepsAccountMetadataAndRestoredExpiryInSettingsCache() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory(), settingsScope = backgroundScope)
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            server.enqueue(MockResponse().setBody("""[{"id":"opaque","username":"target","acct":"target@remote.example","display_name":"Known author","mute_expires_at":"2030-01-01T00:00:00Z"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":"opaque","muting":true,"muting_notifications":false}]"""))
            repository.syncServerSettings(session, setOf(ServerSetting.Mutes)).getOrThrow()
            server.enqueue(MockResponse().setBody("""{"id":"opaque","muting":false}"""))
            repository.setMuted(session, "opaque", false).getOrThrow()
            val before = java.time.Instant.now()
            server.enqueue(MockResponse().setBody("""{"id":"opaque","muting":true,"muting_notifications":false}"""))
            repository.restoreMute(session, "opaque", false, 59).getOrThrow()
            val restored = repository.serverSettings.value.getValue(session.sessionId).mutes!!.single()
            assertEquals("Known author", restored.account.displayName)
            assertEquals("target@remote.example", restored.account.accountName)
            assertEquals(false, restored.relationship.mutingNotifications)
            val expiry = java.time.Instant.parse(restored.muteExpiresAt)
            assertFalse(expiry.isBefore(before.plusSeconds(59)))
            assertFalse(expiry.isAfter(java.time.Instant.now().plusSeconds(59)))
            assertEquals(4, server.requestCount)
        }
    }
}
