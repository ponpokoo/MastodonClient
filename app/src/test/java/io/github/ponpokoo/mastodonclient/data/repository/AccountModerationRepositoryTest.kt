package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.data.local.NotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.common.testAccount
import io.github.ponpokoo.mastodonclient.feature.common.testStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AccountModerationRepositoryTest {
    @Test fun existingMuteWithoutNotificationSuppressionIsRespected() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            server.enqueue(MockResponse().setBody("""[{"id":"author","muting":true,"muting_notifications":false}]"""))
            val relation = repository.getRelationship(session, "author").getOrThrow()
            assertEquals(false, relation.mutingNotifications)
            val post = testStatus().copy(author = testStatus().author.copy(id = "author"))
            assertTrue(repository.moderation.value.hides(session, post))
            assertFalse(repository.moderation.value.hides(session, TimelineNotification("notice", "mention", "", post.author, post)))
        }
    }
    @Test fun hiddenPagesPreserveServerCursorAndDoNotDeclareTheHistoryFinished() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            val account = """{"id":"author","username":"author","acct":"author"}"""
            val status = """{"id":"opaque-row","created_at":"2026-10-05T00:00:00Z","account":$account,"content":"text"}"""
            server.enqueue(MockResponse().setBody("""{"id":"author","muting":true}"""))
            repository.setMuted(session, "author", true).getOrThrow()
            server.enqueue(MockResponse().setBody("[$status]"))
            val home = repository.getHomeTimeline(session).getOrThrow()
            assertTrue(home.statuses.isEmpty())
            assertEquals("opaque-row", home.nextMaxId)
            assertFalse(home.endReached)
            assertNull(repository.getCachedStatus(session, "opaque-row"))
            server.enqueue(MockResponse().setBody("""[{"id":"opaque-notice","type":"follow","created_at":"2026-10-05T00:00:00Z","account":$account}]"""))
            val notices = repository.getNotifications(session).getOrThrow()
            assertTrue(notices.notifications.isEmpty())
            assertEquals("opaque-notice", notices.nextMaxId)
            assertFalse(notices.endReached)
        }
    }
    @Test fun muteUnmuteBlockAndUnblockUseSelectedServerAndKeepOtherSuppression() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            val post = testStatus().copy(author = testStatus().author.copy(id = "opaque-author"))
            val responses = listOf(true to false, true to true, false to true, false to false)
            responses.forEach { (mute, block) -> server.enqueue(MockResponse().setBody("""{"id":"opaque-author","muting":$mute,"blocking":$block}""")) }
            repository.setMuted(session, "opaque-author", true).getOrThrow()
            assertTrue(repository.moderation.value.hides(session, post))
            val first = server.takeRequest()
            assertEquals("/api/v1/accounts/opaque-author/mute", first.path)
            assertEquals("notifications=true", first.body.readUtf8())
            repository.setBlocked(session, "opaque-author", true).getOrThrow()
            assertEquals("/api/v1/accounts/opaque-author/block", server.takeRequest().path)
            repository.setMuted(session, "opaque-author", false).getOrThrow()
            assertTrue(repository.moderation.value.hides(session, post))
            assertEquals("/api/v1/accounts/opaque-author/unmute", server.takeRequest().path)
            repository.setBlocked(session, "opaque-author", false).getOrThrow()
            assertFalse(repository.moderation.value.hides(session, post))
            assertEquals("/api/v1/accounts/opaque-author/unblock", server.takeRequest().path)
            assertFalse(repository.moderation.value.hides(session.copy(sessionId = "other"), post))
        }
    }

    @Test fun failedRemoteChangeDoesNotHideAndDiskFailureDoesNotUndoServerSuccess() = runTest {
        MockWebServer().use { server ->
            val disk = object : NotificationLocalDataSource {
                override suspend fun read(session: AccountSession) = CachedNotifications()
                override suspend fun write(session: AccountSession, notifications: List<TimelineNotification>) = Unit
                override suspend fun writeMarker(session: AccountSession, lastReadId: String?) = Unit
                override suspend fun deleteAccount(sessionId: String) = Unit
                override suspend fun applyModeration(session: AccountSession, state: AccountModerationState) { error("disk full") }
            }
            val repository = DefaultTimelineRepository(ApiClientFactory(), notificationLocalDataSource = disk)
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(500))
            assertTrue(repository.setBlocked(session, "author", true).isFailure)
            assertTrue(repository.moderation.value.relationships.isEmpty())
            server.enqueue(MockResponse().setBody("""{"id":"author","blocking":true}"""))
            assertTrue(repository.setBlocked(session, "author", true).getOrThrow().blocking)
            assertTrue(repository.moderation.value.hasCleanupFailure(session, "author"))
        }
    }

    @Test fun accountAndPostReportsPreserveTheirTargetAndForwardingContract() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testAccount.copy(instanceUrl = server.url("/").toString())
            repeat(2) { server.enqueue(MockResponse().setBody("""{"id":"report"}""")) }
            repository.reportAccount(session, "opaque-author", "reason", true).getOrThrow()
            val account = server.takeRequest()
            assertEquals("/api/v1/reports", account.path)
            val accountBody = account.body.readUtf8()
            assertTrue(accountBody.contains("account_id=opaque-author"))
            assertTrue(accountBody.contains("forward=true"))
            assertFalse(accountBody.contains("status_ids"))
            repository.reportStatus(session, "opaque-author", "opaque-post", "reason").getOrThrow()
            val postBody = server.takeRequest().body.readUtf8()
            assertTrue(postBody.contains("forward=false"))
            assertTrue(postBody.contains("category=other"))
            assertTrue(postBody.contains("status_ids%5B%5D=opaque-post"))
        }
    }
}
