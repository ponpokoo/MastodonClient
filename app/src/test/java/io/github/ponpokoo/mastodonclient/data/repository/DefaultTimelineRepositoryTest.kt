package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineFeed
import io.github.ponpokoo.mastodonclient.domain.model.CreateStatusRequest
import io.github.ponpokoo.mastodonclient.domain.model.SavedTimelineKind
import io.github.ponpokoo.mastodonclient.domain.model.mentionedAccountIdFor
import io.github.ponpokoo.mastodonclient.domain.model.replyToAccountName
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTimelineRepositoryTest {
    @Test fun imagePreviewDimensionsComeFromSmallMetadataWithoutAssumingAServerSize() = runTest {
        MockWebServer().use { server ->
            val media = """"media_attachments":[
                {"id":"known","type":"image","url":"https://cdn.example/original","preview_url":"https://cdn.example/preview",
                 "meta":{"original":{"width":2048,"height":1024},"small":{"width":720,"height":360}}},
                {"id":"unknown","type":"image","url":"https://cdn.example/original","meta":{"width":2048,"height":1024}}
            ]"""
            server.enqueue(MockResponse().setBody("[" + basicStatusJson("images").dropLast(1) + ",$media}]"))
            val attachments = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single().mediaAttachments
            assertEquals(720, attachments.first().previewWidth)
            assertEquals(360, attachments.first().previewHeight)
            assertEquals(2f, attachments.first().aspectRatio)
            assertNull(attachments.last().previewWidth)
            assertNull(attachments.last().previewHeight)
        }
    }

    @Test fun interactionPagesUseLinkCursorsAndTheSelectedInstanceForAllThreeKinds() = runTest {
        for (kind in listOf("favourited_by", "reblogged_by", "emoji_reactioned_by")) {
            MockWebServer().use { server ->
                val account = """{"id":"account-id","username":"alice","acct":"alice"}"""
                val body = if (kind == "emoji_reactioned_by") """[{"name":"👍","account":$account}]""" else "[$account]"
                server.enqueue(MockResponse().setBody(body).addHeader("Link",
                    "<https://different.example/api/v1/statuses/post/$kind?max_id=opaque%2Bcursor&limit=40>; rel=\"next\""))
                server.enqueue(MockResponse().setBody(body))
                val repository = DefaultTimelineRepository(ApiClientFactory())
                val session = testSession(server)
                suspend fun load(cursor: String? = null) = when (kind) {
                    "favourited_by" -> repository.getFavouritedBy(session, "post", cursor)
                    "reblogged_by" -> repository.getRebloggedBy(session, "post", cursor)
                    else -> repository.getEmojiReactionedBy(session, "post", "👍", cursor)
                }.getOrThrow()
                val first = load()
                assertEquals("opaque+cursor", first.nextMaxId)
                assertFalse(first.endReached)
                assertEquals("account-id", first.accounts.single().id)
                val last = load(first.nextMaxId)
                assertTrue(last.endReached)
                assertNull(last.nextMaxId)
                val initialRequest = server.takeRequest().requestUrl!!
                assertEquals("/api/v1/statuses/post/$kind", initialRequest.encodedPath)
                assertEquals("40", initialRequest.queryParameter("limit"))
                assertNull(initialRequest.queryParameter("max_id"))
                val nextRequest = server.takeRequest().requestUrl!!
                assertEquals("opaque+cursor", nextRequest.queryParameter("max_id"))
                assertEquals(server.port, nextRequest.port)
            }
        }
    }

    @Test fun reactionPageWithNoMatchingEmojiRetainsItsServerCursor() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"name":"🎉","account":{"id":"other","username":"bob","acct":"bob"}}]""",
            ).addHeader("Link", "<${server.url("/api/v1/statuses/post/emoji_reactioned_by?max_id=reaction-id")}>; rel=\"next\""))
            val page = DefaultTimelineRepository(ApiClientFactory())
                .getEmojiReactionedBy(testSession(server), "post", "👍").getOrThrow()
            assertTrue(page.accounts.isEmpty())
            assertEquals("reaction-id", page.nextMaxId)
            assertFalse(page.endReached)
        }
    }

    @Test fun interactionPageHttpFailuresAreNotReportedAsEmptySuccessfulLists() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(403).setBody("{}")) }
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            assertTrue(repository.getFavouritedBy(session, "post").isFailure)
            assertTrue(repository.getRebloggedBy(session, "post").isFailure)
            assertTrue(repository.getEmojiReactionedBy(session, "post", "👍").isFailure)
        }
    }

    @Test fun audioAttachmentsPreserveTypeAndOriginalUrlWithoutPreviewOrMimeFiltering() = runTest {
        MockWebServer().use { server ->
            val media = """"media_attachments":[
                {"id":"mp3","type":"audio","url":"https://cdn.example/source.mp3","preview_url":"https://cdn.example/cover.png","mime_type":"audio/mpeg","meta":{"duration":12}},
                {"id":"wav","type":"audio","url":"https://cdn.example/source.wav","preview_url":null,"mime_type":"audio/wav"},
                {"id":"pending","type":"audio","url":null,"preview_url":"https://cdn.example/cover.png"}
            ]"""
            val status = basicStatusJson("audio-post")
                .replace("\"acct\":\"alice\"", "\"acct\":\"alice\",\"avatar\":\"https://example.org/author.png\"")
            server.enqueue(MockResponse().setBody("[" + status.dropLast(1) + ",$media}]"))
            val attachments = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single().mediaAttachments
            assertEquals(listOf("audio", "audio", "audio"), attachments.map { it.type })
            assertEquals(listOf("https://cdn.example/source.mp3", "https://cdn.example/source.wav", null), attachments.map { it.url })
            assertEquals(listOf("https://cdn.example/cover.png", null, "https://cdn.example/cover.png"), attachments.map { it.previewUrl })
            assertTrue(attachments.all { it.authorAvatarUrl == "https://example.org/author.png" })
        }
    }
    @Test fun homeShortPagesKeepPagingUntilServerReturnsEmpty() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("opaque-home-row")}]"))
            server.enqueue(MockResponse().setBody("[]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            val first = repository.getHomeTimeline(session, limit = 20).getOrThrow()
            assertFalse(first.endReached)
            assertEquals("opaque-home-row", first.nextMaxId)
            val last = repository.getHomeTimeline(session, maxId = first.nextMaxId).getOrThrow()
            assertTrue(last.endReached)
            assertTrue(last.statuses.isEmpty())
        }
    }

    @Test fun regeneratingHomeIsRetryableRatherThanAnEmptyTerminalPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("[]"))
            val result = DefaultTimelineRepository(ApiClientFactory()).getHomeTimeline(testSession(server))
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun editableStatusPreservesLanguageAndAllowsServersToOmitIt() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            for (language in listOf("en", null)) {
                server.enqueue(MockResponse().setBody("""{"id":"post","text":"hello","spoiler_text":""}"""))
                val statusJson = basicStatusJson("post").dropLast(1) +
                    (language?.let { ",\"language\":\"$it\"}" } ?: "}")
                server.enqueue(MockResponse().setBody(statusJson))
                assertEquals(language, repository.getEditableStatus(session, "post").getOrThrow().language)
            }
        }
    }

    @Test
    fun mediaProcessingAcceptsEmpty206ThenReadyResponseWithoutReupload() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206))
            server.enqueue(MockResponse().setBody("""{"id":"opaque-media","type":"video","url":"https://example.test/video.mp4","description":"ALT"}"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            assertFalse(repository.checkMedia(session, "opaque-media").getOrThrow().ready)
            val ready = repository.checkMedia(session, "opaque-media").getOrThrow()
            assertTrue(ready.ready)
            assertEquals("ALT", ready.description)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/api/v1/media/opaque-media", request.path)
            }
        }
    }

    @Test
    fun mediaProcessingPreservesHttpFailureAndSupportsClearingAlt() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"processing failed"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"opaque-media","type":"image","url":"https://example.test/image.jpg","description":""}"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            val failure = repository.checkMedia(session, "opaque-media").exceptionOrNull()
            assertEquals(io.github.ponpokoo.mastodonclient.domain.model.RequestFailure.Unprocessable,
                (failure as io.github.ponpokoo.mastodonclient.domain.model.RequestException).failure)
            assertEquals(422, (failure.cause as retrofit2.HttpException).code())
            repository.updateMediaDescription(session, "opaque-media", "").getOrThrow()
            server.takeRequest()
            val update = server.takeRequest()
            assertEquals("PUT", update.method)
            assertEquals("description=", update.body.readUtf8())
        }
    }

    @Test
    fun acceptedUploadReturnsIdAndProgressBeforeServerProcessingCompletes() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"pending-video","type":"video","url":null}"""))
            val file = java.io.File.createTempFile("media-upload", ".mp4")
            try {
                file.writeBytes(ByteArray(32_768) { 7 })
                val progress = mutableListOf<Float>()
                val media = DefaultTimelineRepository(ApiClientFactory()).uploadMedia(testSession(server),
                    io.github.ponpokoo.mastodonclient.domain.model.MediaUpload(
                        "video.mp4", "video/mp4", file.absolutePath, null, { progress += it },
                    )).getOrThrow()
                assertEquals("pending-video", media.id)
                assertFalse(media.ready)
                assertEquals(1f, progress.last())
                assertEquals(1, server.requestCount)
                assertEquals("/api/v2/media", server.takeRequest().path)
            } finally { file.delete() }
        }
    }

    @Test
    fun readsNotificationMarkerAsAnOpaqueStringAndAllowsMissingMarker() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"notifications":{"last_read_id":"notification-opaque-id"}}""",
            ))
            server.enqueue(MockResponse().setBody("{}"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            assertEquals("notification-opaque-id", repository.getNotificationMarker(session).getOrThrow())
            assertNull(repository.getNotificationMarker(session).getOrThrow())
            repeat(2) {
                assertEquals("notifications", server.takeRequest().requestUrl?.queryParameter("timeline[]"))
            }
        }
    }

    @Test
    fun preservesLockedAccountsAndFollowRequestRelationships() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"private-one","username":"alice","acct":"alice","locked":true}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"id":"private-one","following":false,"followed_by":true,"requested":true}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val account = repository.getAccountListPage(session, "me", followers = true)
                .getOrThrow().accounts.single()
            val relationship = repository.getRelationships(session, listOf(account.id))
                .getOrThrow().getValue(account.id)

            assertTrue(account.locked)
            assertTrue(relationship.followedBy)
            assertTrue(relationship.requested)
            assertFalse(relationship.following)
        }
    }

    @Test
    fun followsUseLinkCursorInsteadOfLastAccountId() = runTest {
        MockWebServer().use { server ->
            val nextUrl = server.url("/api/v1/accounts/me/followers?limit=40&max_id=follow-edge-123")
            server.enqueue(MockResponse()
                .addHeader("Link", "<$nextUrl>; rel=\"next\"")
                .setBody("""[{"id":"account-999","username":"alice","acct":"alice"}]"""))
            server.enqueue(MockResponse().setBody("""[{"id":"account-500","username":"bob","acct":"bob"}]"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val first = repository.getAccountListPage(session, "me", followers = true).getOrThrow()
            assertEquals("follow-edge-123", first.nextMaxId)
            assertFalse(first.endReached)
            val second = repository.getAccountListPage(session, "me", followers = true, first.nextMaxId).getOrThrow()
            assertEquals(listOf("account-500"), second.accounts.map { it.id })
            assertTrue(second.endReached)
            server.takeRequest()
            assertEquals("follow-edge-123", server.takeRequest().requestUrl?.queryParameter("max_id"))
        }
    }

    @Test
    fun readsReactionAccountsFromPlainAndGroupedResponses() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"one","username":"alice","acct":"alice","display_name":"Alice"}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"name":"👍","accounts":[{"id":"two","username":"bob","acct":"bob"}]},{"name":"🎉","accounts":[{"id":"three","username":"cara","acct":"cara"}]}]""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"emoji":"👍","account":{"id":"four","username":"dan","acct":"dan"}}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            assertEquals(listOf("one"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().accounts.map { it.id })
            assertEquals(listOf("two"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().accounts.map { it.id })
            assertEquals(listOf("four"), repository.getEmojiReactionedBy(session, "status", "👍").getOrThrow().accounts.map { it.id })
        }
    }

    @Test
    fun mapsStatusAndAuthorEmojisFromTimelineResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"emoji-status","created_at":"2026-09-08T00:00:00Z","content":"<p>:electric:</p>","emojis":[{"shortcode":"electric","url":"https://example.com/electric.gif","static_url":"https://example.com/electric.png"}],"account":{"id":"a","username":"alice","acct":"alice","display_name":"Alice :twitch:","emojis":[{"shortcode":"twitch","url":"https://example.com/twitch.png"}]}}]""",
            ))

            val status = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single()

            assertEquals(mapOf("electric" to "https://example.com/electric.gif"), status.customEmojis)
            assertEquals(mapOf("twitch" to "https://example.com/twitch.png"), status.author.customEmojis)
        }
    }

    @Test
    fun mapsMentionAccountIdAndQuoteApproval() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"mention-status","created_at":"2026-09-08T00:00:00Z","in_reply_to_id":"parent-status","in_reply_to_account_id":"account-42","content":"<p><a href=\"https://social.example/@alice\">@alice</a></p>","mentions":[{"id":"account-42","username":"alice","acct":"alice@social.example","url":"https://social.example/@alice"}],"quote_approval":{"current_user":"automatic","automatic":[],"manual":[]},"account":{"id":"author","username":"bob","acct":"bob"}}]""",
            ))

            val status = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server)).getOrThrow().statuses.single()

            assertEquals("account-42", status.mentionedAccountIdFor("https://social.example/@alice/"))
            assertEquals(null, status.mentionedAccountIdFor("https://unrelated.example/@alice"))
            assertEquals("automatic", status.quoteApproval)
            assertEquals("parent-status", status.inReplyToId)
            assertEquals("account-42", status.inReplyToAccountId)
            assertEquals("alice@social.example", status.replyToAccountName())
        }
    }

    @Test
    fun cachedStatusesAreIsolatedByAccountAndInstance() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("shared")} ]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            repository.getHomeTimeline(session).getOrThrow()
            assertEquals("shared", repository.getCachedStatus(session, "shared")?.statusId)
            val other = session.copy(sessionId = "second")
            server.enqueue(MockResponse().setBody("[${basicStatusJson("shared").replace("hello", "other account")}]"))
            repository.getHomeTimeline(other).getOrThrow()
            assertEquals("hello", repository.getCachedStatus(session, "shared")?.contentHtml)
            assertEquals("other account", repository.getCachedStatus(other, "shared")?.contentHtml)
            assertEquals(null, repository.getCachedStatus(session.copy(sessionId = "other"), "shared"))
            assertEquals(null, repository.getCachedStatus(session.copy(instanceUrl = "https://other.social"), "shared"))
        }
    }

    @Test
    fun statusCacheEvictsOldEntries() = runTest {
        MockWebServer().use { server ->
            val statuses = (0..500).joinToString(",") { basicStatusJson(it.toString()) }
            server.enqueue(MockResponse().setBody("[$statuses]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            repository.getHomeTimeline(session).getOrThrow()
            assertEquals(null, repository.getCachedStatus(session, "0"))
            assertEquals("500", repository.getCachedStatus(session, "500")?.statusId)
        }
    }

    @Test
    fun loadsHashtagTimelineWithTagEndpoint() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("tagged")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())

            val page = repository.getHashtagTimeline(
                session = testSession(server),
                hashtag = "Android",
                maxId = "cursor",
                limit = 1,
            ).getOrThrow()

            assertEquals("tagged", page.statuses.single().statusId)
            assertEquals(
                "/api/v1/timelines/tag/Android?max_id=cursor&limit=1",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun shortPublicPageKeepsCursorUntilEmptyResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("cursor")}]"))
            server.enqueue(MockResponse().setBody("[]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            val first = repository.getTimeline(session, TimelineFeed.Local, limit = 20).getOrThrow()
            assertFalse(first.endReached)
            assertEquals("cursor", first.nextMaxId)
            val last = repository.getTimeline(session, TimelineFeed.Local, maxId = first.nextMaxId, limit = 20).getOrThrow()
            assertTrue(last.endReached)
            assertNull(last.nextMaxId)
            server.takeRequest()
            assertEquals("cursor", server.takeRequest().requestUrl!!.queryParameter("max_id"))
        }
    }

    @Test
    fun loadsLocalAndFederatedTimelinesWithPublicEndpoint() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[${basicStatusJson("local")}]"))
            server.enqueue(MockResponse().setBody("[${basicStatusJson("federated")}]"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            repository.getTimeline(session, TimelineFeed.Local, limit = 1).getOrThrow()
            repository.getTimeline(session, TimelineFeed.Federated, limit = 1).getOrThrow()

            assertEquals(
                "/api/v1/timelines/public?local=true&remote=false&limit=1",
                server.takeRequest().path,
            )
            assertEquals(
                "/api/v1/timelines/public?local=false&remote=false&limit=1",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun loadsOwnProfileAndStatuses() = runTest {
        MockWebServer().use { server ->
            val requestsStarted = java.util.concurrent.CountDownLatch(3)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestsStarted.countDown()
                    if (!requestsStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        return MockResponse().setResponseCode(504)
                    }
                    return when (request.path) {
                    "/api/v1/accounts/me" -> MockResponse().setBody(
                        """{"id":"me","username":"alice","acct":"alice","display_name":"Alice","note":"<p>Bio</p>","followers_count":12,"following_count":8,"statuses_count":34}""",
                    )
                    "/api/v1/accounts/me/statuses?limit=20&exclude_reblogs=false&exclude_replies=true&only_media=false&pinned=false" ->
                        MockResponse().setBody("[${basicStatusJson("profile-status")}]" )
                    "/api/v1/accounts/me/statuses?limit=20&exclude_reblogs=false&exclude_replies=false&only_media=false&pinned=true" ->
                        MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val session = testSession(server).copy(accountId = "me")

            val profile = DefaultTimelineRepository(ApiClientFactory())
                .getProfile(session)
                .getOrThrow()

            assertEquals("Alice", profile.author.displayName)
            assertEquals(12, profile.followersCount)
            assertEquals("profile-status", profile.statuses.single().statusId)
        }
    }

    @Test
    fun loadsNotificationsAndSearchResults() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":"n1","type":"mention","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"status":${basicStatusJson("mentioned")}}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"accounts":[{"id":"a","username":"alice","acct":"alice"}],"statuses":[${basicStatusJson("found")}],"hashtags":[{"name":"android","url":"https://example.social/tags/android"}]}""",
                ),
            )
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val notificationPage = repository.getNotifications(session).getOrThrow()
            val notifications = notificationPage.notifications
            val results = repository.search(session, "android").getOrThrow().results

            assertEquals("mention", notifications.single().type)
            assertEquals("n1", notificationPage.nextMaxId)
            assertFalse(notificationPage.endReached)
            assertEquals("found", results.statuses.single().statusId)
            assertEquals(notifications.single().status, repository.getCachedStatus(session, "mentioned"))
            assertEquals(results.statuses.single(), repository.getCachedStatus(session, "found"))
            assertTrue(results.hashtags.isEmpty())
            assertTrue(results.accounts.isEmpty())
            assertEquals("/api/v1/notifications?limit=40", server.takeRequest().path)
            assertEquals("/api/v2/search?q=android&type=statuses&limit=20&offset=0&resolve=false", server.takeRequest().path)
        }
    }

    @Test
    fun emptyNotificationPageEndsPagination() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))

            val page = DefaultTimelineRepository(ApiClientFactory())
                .getNotifications(testSession(server), maxId = "n1")
                .getOrThrow()

            assertTrue(page.notifications.isEmpty())
            assertNull(page.nextMaxId)
            assertTrue(page.endReached)
            assertEquals("/api/v1/notifications?max_id=n1&limit=40", server.takeRequest().path)
        }
    }

    @Test
    fun loadsServerAnnouncements() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":"notice-1","content":"<p>Maintenance</p>","published_at":"2026-09-08T00:00:00Z","read":false}]""",
                ),
            )

            val announcements = DefaultTimelineRepository(ApiClientFactory())
                .getAnnouncements(testSession(server))
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("/api/v1/announcements", request.path)
            assertEquals("notice-1", announcements.single().id)
            assertEquals("<p>Maintenance</p>", announcements.single().contentHtml)
        }
    }

    @Test
    fun loadsAuthenticatedPageAndMapsBoostedStatus() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "id": "outer-20",
                        "created_at": "2026-09-08T00:00:00.000Z",
                        "account": {
                          "id": "booster-1",
                          "username": "booster",
                          "acct": "booster@example.social",
                          "display_name": "Booster"
                        },
                        "reblog": {
                          "id": "status-10",
                          "created_at": "2026-09-07T23:00:00.000Z",
                          "account": {
                            "id": "author-1",
                            "username": "author",
                            "acct": "author@example.social",
                            "display_name": "Author",
                            "avatar": "https://cdn.example/avatar.png"
                          },
                          "content": "<p>Hello <strong>Mastodon</strong></p>",
                          "replies_count": 2,
                          "reblogs_count": 3,
                          "favourites_count": 5,
                          "media_attachments": [
                            {
                              "id": "media-1",
                              "type": "image",
                              "url": "https://cdn.example/image.png",
                              "preview_url": "https://cdn.example/preview.png",
                              "description": "ALT text"
                            }
                          ]
                        }
                      }
                    ]
                    """.trimIndent(),
                ),
            )
            val session = AccountSession(
                sessionId = "session",
                instanceUrl = server.url("/").toString().removeSuffix("/"),
                accountId = "me",
                username = "me",
                displayName = "Me",
                avatarUrl = "",
                accessToken = "secret-token",
            )

            val page = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(session, maxId = "cursor-30", limit = 1)
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertEquals("/api/v1/timelines/home?max_id=cursor-30&limit=1", request.path)
            assertEquals("outer-20", page.nextMaxId)
            assertFalse(page.endReached)
            assertEquals("status-10", page.statuses.single().statusId)
            assertEquals("Author", page.statuses.single().author.displayName)
            assertEquals("Booster", page.statuses.single().boostedBy?.displayName)
            assertEquals("ALT text", page.statuses.single().mediaAttachments.single().description)
        }
    }

    @Test
    fun mapsReactionOriginalImagesWithStaticFallback() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """
                [{
                  "id":"42","created_at":"2026-09-08T00:00:00Z",
                  "account":{"id":"author","username":"alice","acct":"alice","display_name":"Alice"},
                  "content":"<p>Hello</p>",
                  "emoji_reactions":[
                    {"name":"animated","url":"https://example.com/emoji.gif","static_url":"https://example.com/emoji.png"},
                    {"name":"missing-original","static_url":"https://example.com/fallback.png"},
                    {"name":"blank-original","url":" ","static_url":"https://example.com/fallback.png"},
                    {"name":"unicode"},
                    {"name":"empty-images","url":"","static_url":" "}
                  ]
                }]
                """.trimIndent(),
            ))

            val reactions = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server))
                .getOrThrow().statuses.single().reactions

            assertEquals(
                listOf(
                    "https://example.com/emoji.gif",
                    "https://example.com/fallback.png",
                    "https://example.com/fallback.png",
                    null,
                    null,
                ),
                reactions.map { it.imageUrl },
            )
        }
    }

    @Test
    fun loadsStatusDetailAndMapsApplicationAndFedibirdReactions() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/statuses/42" -> MockResponse().setBody(
                        """
                        {
                          "id":"42","created_at":"2026-09-08T00:00:00Z",
                          "account":{"id":"author","username":"alice","acct":"alice","display_name":"Alice"},
                          "content":"<p>Hello</p>",
                          "application":{"name":"Tusky","website":"https://example.com"},
                          "emoji_reactions":[{"name":"👍","count":3,"me":true,"account_ids":["me"]}]
                        }
                        """.trimIndent(),
                    )
                    "/api/v1/statuses/42/context" ->
                        MockResponse().setBody("""{"ancestors":[],"descendants":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val session = AccountSession(
                sessionId = "session",
                instanceUrl = server.url("/").toString().removeSuffix("/"),
                accountId = "me",
                username = "me",
                displayName = "Me",
                avatarUrl = "",
                accessToken = "token",
            )

            val detail = DefaultTimelineRepository(ApiClientFactory())
                .getStatusDetail(session, "42")
                .getOrThrow()

            assertEquals(
                setOf("/api/v1/statuses/42", "/api/v1/statuses/42/context"),
                setOf(server.takeRequest().path, server.takeRequest().path),
            )
            assertEquals("Tusky", detail.status.applicationName)
            assertEquals("👍", detail.status.reactions.single().name)
            assertEquals(true, detail.status.reactions.single().reactedByMe)
            assertEquals(true, detail.status.supportsEmojiReactions)
        }
    }

    @Test
    fun postsReplyWithIdempotencyKey() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("99")))
            val session = testSession(server)

            DefaultTimelineRepository(ApiClientFactory())
                .createStatus(session, "返信です", "42", "request-key")
                .getOrThrow()

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/statuses", request.path)
            assertEquals("request-key", request.getHeader("Idempotency-Key"))
            val body = request.body.readUtf8()
            assertEquals(true, body.contains("status=%E8%BF%94%E4%BF%A1%E3%81%A7%E3%81%99"))
            assertEquals(true, body.contains("in_reply_to_id=42"))
        }
    }

    @Test
    fun postsCompleteComposerRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("100")))
            val session = testSession(server)

            DefaultTimelineRepository(ApiClientFactory()).createStatus(
                session = session,
                request = CreateStatusRequest(
                    text = "本文",
                    mediaIds = listOf("media-1"),
                    spoilerText = "注意",
                    sensitive = true,
                    visibility = "private",
                    language = "ja",
                ),
                idempotencyKey = "complete-key",
            ).getOrThrow()

            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("complete-key", request.getHeader("Idempotency-Key"))
            assertEquals(true, body.contains("media_ids%5B%5D=media-1"))
            assertEquals(true, body.contains("visibility=private"))
            assertEquals(true, body.contains("sensitive=true"))
            assertEquals(true, body.contains("language=ja"))
        }
    }

    @Test
    fun postsNativeQuoteWithoutTurningItIntoAReply() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("quoted")))

            DefaultTimelineRepository(ApiClientFactory()).createStatus(
                testSession(server),
                CreateStatusRequest(text = "引用本文", quotedStatusId = "source-42"),
                "quote-key",
            ).getOrThrow()

            val body = server.takeRequest().body.readUtf8()
            assertEquals(true, body.contains("quoted_status_id=source-42"))
            assertEquals(false, body.contains("in_reply_to_id="))
        }
    }

    @Test
    fun readsComposerLimitsAndCustomEmoji() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"configuration":{"statuses":{"max_characters":800,"max_media_attachments":6},"media_attachments":{"description_limit":1200,"supported_mime_types":["image/png"]}}}""",
            ))
            server.enqueue(MockResponse().setBody(
                """[{"shortcode":"party","url":"https://cdn.example/party.gif","static_url":"https://cdn.example/party.png","category":"People"}]""",
            ))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val limits = repository.getComposerConfiguration(session).getOrThrow()
            val emoji = repository.getCustomEmojis(session).getOrThrow().single()

            assertEquals(800, limits.maxCharacters)
            assertEquals(6, limits.maxMediaAttachments)
            assertEquals(1200, limits.mediaDescriptionLimit)
            assertEquals(setOf("image/png"), limits.supportedMimeTypes)
            assertEquals("party", emoji.shortcode)
        }
    }

    @Test
    fun mapsOptionalMediaDimensionsAcrossServerMetadataFormats() = runTest {
        val cases = listOf(
            """{"original":{"width":120,"height":600},"small":{"width":100,"height":100}}""" to 0.2f,
            """{"width":1280,"height":720,"duration":5}""" to (1280f / 720),
            """{"original":{"aspect":1.5}}""" to 1.5f,
            """{"original":{"width":0,"height":-1,"aspect":0},"small":{"width":400,"height":200}}""" to 2f,
            """{"original":{"width":0,"height":0,"aspect":-1}}""" to null,
            "null" to null,
        )
        MockWebServer().use { server ->
            for ((meta, expectedRatio) in cases) {
                server.enqueue(MockResponse().setBody(
                    """[${basicStatusJson("post").dropLast(1)},"media_attachments":[{"id":"image","type":"image","meta":$meta},{"id":"legacy","type":"image"}]}]""",
                ))
                val media = DefaultTimelineRepository(ApiClientFactory())
                    .getHomeTimeline(testSession(server)).getOrThrow().statuses.single().mediaAttachments
                assertEquals(expectedRatio, media.first().aspectRatio)
                assertNull(media.last().aspectRatio)
            }
        }
    }

    @Test fun composerCapabilitiesAreCachedPerInstanceWithExpiryAndConcurrentReuse() = runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                val png = """{"configuration":{"media_attachments":{"supported_mime_types":["image/png"]}}}"""
                val audio = """{"configuration":{"media_attachments":{"supported_mime_types":["audio/mpeg"]}}}"""
                first.enqueue(MockResponse().setBody(png))
                first.enqueue(MockResponse().setBody(audio))
                second.enqueue(MockResponse().setBody(audio))
                var now = 0L
                val repository = DefaultTimelineRepository(ApiClientFactory(), configurationClock = { now })
                val a = testSession(first)
                val results = (1..3).map { async { repository.getComposerConfiguration(a).getOrThrow() } }.awaitAll()
                assertTrue(results.all { it.supportedMimeTypes == setOf("image/png") })
                assertEquals(1, first.requestCount)
                assertEquals(setOf("audio/mpeg"), repository.getComposerConfiguration(testSession(second)).getOrThrow().supportedMimeTypes)
                assertEquals(setOf("image/png"), repository.getComposerConfiguration(a.copy(sessionId = "another-account")).getOrThrow().supportedMimeTypes)
                now = 300_000L
                assertEquals(setOf("audio/mpeg"), repository.getComposerConfiguration(a).getOrThrow().supportedMimeTypes)
                assertEquals(2, first.requestCount)
                assertNull(first.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test fun composerCapabilitiesFallBackOn404AndDoNotCacheFailuresOrMissingLists() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"configuration":{"statuses":{"max_media_attachments":2}}}"""))
            server.enqueue(MockResponse().setBody("""{"configuration":{"media_attachments":{"supported_mime_types":[]}}}"""))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            assertTrue(repository.getComposerConfiguration(session).isFailure)
            val missing = repository.getComposerConfiguration(session).getOrThrow()
            assertEquals(2, missing.maxMediaAttachments)
            assertNull(missing.supportedMimeTypes)
            assertEquals(emptySet<String>(), repository.getComposerConfiguration(session).getOrThrow().supportedMimeTypes)
            assertEquals(emptySet<String>(), repository.getComposerConfiguration(session).getOrThrow().supportedMimeTypes)
            assertEquals(4, server.requestCount)
            assertEquals("/api/v2/instance", server.takeRequest().path)
            assertEquals("/api/v2/instance", server.takeRequest().path)
            assertEquals("/api/v1/instance", server.takeRequest().path)
        }
    }

    @Test
    fun mapsPreviewCardAndDistinguishesUnsupportedReactions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    [${basicStatusJson("1").dropLast(1)},
                      "card":{"url":"https://example.com","title":"Example","description":"Summary","image":"https://example.com/image.jpg","width":1200,"height":630}
                    }]
                    """.trimIndent(),
                ),
            )
            val page = DefaultTimelineRepository(ApiClientFactory())
                .getHomeTimeline(testSession(server))
                .getOrThrow()

            assertEquals("Example", page.statuses.single().previewCard?.title)
            assertEquals(1200f / 630, page.statuses.single().previewCard?.aspectRatio)
            assertFalse(page.statuses.single().supportsEmojiReactions)
        }
    }

    @Test
    fun favouritesAndAddsFedibirdReactionUsingExpectedEndpoints() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            repository.setFavourite(session, "1", true).getOrThrow()
            repository.setFedibirdReaction(session, "1", "👍").getOrThrow()

            val favouriteRequest = server.takeRequest()
            assertEquals("POST", favouriteRequest.method)
            assertEquals("/api/v1/statuses/1/favourite", favouriteRequest.path)
            val reactionRequest = server.takeRequest()
            assertEquals("PUT", reactionRequest.method)
            assertEquals("/api/v1/statuses/1/emoji_reactions/%F0%9F%91%8D", reactionRequest.path)
        }
    }

    @Test
    fun reusesRemoteReactionWithItsServerDomain() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """[{"id":"1","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"emoji_reactions":[{"name":"ee","domain":"misskey.example","count":1,"url":"https://misskey.example/ee.png"}]}]""",
            ))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val reaction = repository.getHomeTimeline(session).getOrThrow().statuses.single().reactions.single()
            assertEquals("ee", reaction.name)
            assertEquals("ee@misskey.example", reaction.apiName)
            repository.setFedibirdReaction(session, "1", reaction.apiName).getOrThrow()

            server.takeRequest()
            assertEquals("/api/v1/statuses/1/emoji_reactions/ee@misskey.example", server.takeRequest().path)
        }
    }

    @Test
    fun recordsOnlySuccessfulNewReactions() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val recorded = mutableListOf<String>()
            val repository = DefaultTimelineRepository(ApiClientFactory(),
                onReactionSucceeded = { _, emoji -> recorded += emoji })
            val session = testSession(server)

            repository.setFedibirdReaction(session, "1", "👍").getOrThrow()
            assertTrue(repository.setFedibirdReaction(session, "1", "🎉").isFailure)
            repository.setFedibirdReaction(session, "1", null).getOrThrow()

            assertEquals(listOf("👍"), recorded)
        }
    }

    @Test
    fun savedPostsUseRelationshipCursorFromLinkRatherThanStatusId() = runTest {
        for (kind in listOf(SavedTimelineKind.Bookmarks, SavedTimelineKind.Favourites)) {
            MockWebServer().use { server ->
                val path = if (kind == SavedTimelineKind.Bookmarks) "bookmarks" else "favourites"
                server.enqueue(MockResponse().setBody("[${basicStatusJson("status-id")}]")
                    .setHeader("Link", "<${server.url("/api/v1/$path?max_id=relationship-next")}>; rel=\"next\""))
                server.enqueue(MockResponse().setBody("[${basicStatusJson("older-status")}]")
                    .setHeader("Link", "<${server.url("/api/v1/$path?min_id=previous")}>; rel=\"prev\""))
                val repository = DefaultTimelineRepository(ApiClientFactory())
                val session = testSession(server)
                val page = repository.getSavedTimeline(session, kind).getOrThrow()
                assertEquals("relationship-next", page.nextMaxId)
                assertFalse(page.endReached)
                val last = repository.getSavedTimeline(session, kind, maxId = page.nextMaxId).getOrThrow()
                assertTrue(last.endReached)
                assertNull(last.nextMaxId)
                assertEquals("/api/v1/$path?limit=20", server.takeRequest().path)
                assertEquals("/api/v1/$path?max_id=relationship-next&limit=20", server.takeRequest().path)
            }
        }
    }

    @Test
    fun savedPostsHttpFailureIsNotAnEmptySuccessfulPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val result = DefaultTimelineRepository(ApiClientFactory()).getSavedTimeline(testSession(server), SavedTimelineKind.Bookmarks)
            assertTrue(result.isFailure)
            assertEquals(403, (result.exceptionOrNull() as retrofit2.HttpException).code())
        }
    }

    @Test
    fun loadsListsAndSelectedListTimeline() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"friends","title":"友だち"}]"""))
            server.enqueue(MockResponse().setBody("[${basicStatusJson("list-status")}]") )
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            val list = repository.getLists(session).getOrThrow().single()
            val page = repository.getSavedTimeline(session, SavedTimelineKind.List, list.id).getOrThrow()

            assertEquals("友だち", list.title)
            assertEquals("list-status", page.statuses.single().statusId)
            assertEquals("/api/v1/lists", server.takeRequest().path)
            assertEquals("/api/v1/timelines/list/friends?limit=20", server.takeRequest().path)
        }
    }

    @Test fun listAccountsFollowLinkCursorAndRemovalUsesOnlyTheChosenMember() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"remote-user","username":"alice","acct":"alice@remote.example"}]""")
                .addHeader("Link", "<https://other.example/api/v1/lists/friends/accounts?max_id=opaque%2Bcursor>; rel=\"next\""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("{}"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)
            val page = repository.getListAccounts(session, "friends").getOrThrow()
            assertEquals("remote-user", page.accounts.single().id)
            assertEquals("opaque+cursor", page.nextMaxId)
            assertFalse(page.endReached)
            assertTrue(repository.getListAccounts(session, "friends", page.nextMaxId).getOrThrow().endReached)
            repository.removeAccountFromList(session, "friends", page.accounts.single().id).getOrThrow()
            assertEquals("/api/v1/lists/friends/accounts?limit=40", server.takeRequest().path)
            assertEquals("/api/v1/lists/friends/accounts?max_id=opaque%2Bcursor&limit=40", server.takeRequest().path)
            val removal = server.takeRequest()
            assertEquals("DELETE", removal.method)
            assertEquals("/api/v1/lists/friends/accounts", removal.path)
            assertEquals("account_ids%5B%5D=remote-user", removal.body.readUtf8())
        }
    }

    @Test fun listAccountFailuresRemainFailuresRatherThanEmptyOrRemovedAccounts() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            assertTrue(repository.getListAccounts(testSession(server), "friends").isFailure)
            assertTrue(repository.removeAccountFromList(testSession(server), "friends", "remote-user").isFailure)
        }
    }

    @Test fun addsListMemberUsingStringIdsAndTheSelectedInstance() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val result = DefaultTimelineRepository(ApiClientFactory())
                .addAccountToList(testSession(server), "friends", "remote-user")

            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/lists/friends/accounts", request.path)
            assertEquals("account_ids%5B%5D=remote-user", request.body.readUtf8())
        }
    }

    @Test fun duplicateListMemberReturnsAnAlreadyAddedError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422)
                .setBody("""{"error":"Validation failed: Account has already been taken"}"""))
            val result = DefaultTimelineRepository(ApiClientFactory())
                .addAccountToList(testSession(server), "friends", "remote-user")

            assertTrue(result.isFailure)
            assertEquals("このリストには追加済みです", result.exceptionOrNull()?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unrelatedListErrorsAreNotReportedAsAlreadyAdded() = runTest {
        MockWebServer().use { server ->
            val repository = DefaultTimelineRepository(ApiClientFactory())
            for (code in listOf(401, 403, 404, 500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))
                val error = repository.addAccountToList(testSession(server), "friends", "remote-user").exceptionOrNull()
                assertEquals(code, (error as retrofit2.HttpException).code())
            }
        }
    }

    @Test
    fun pinsAndUnpinsUsingExpectedEndpoints() = runTest {
        MockWebServer().use { server ->
            val pinned = basicStatusJson("1").dropLast(1) + ",\"pinned\":true}"
            server.enqueue(MockResponse().setBody(pinned))
            server.enqueue(MockResponse().setBody(basicStatusJson("1")))
            val repository = DefaultTimelineRepository(ApiClientFactory())
            val session = testSession(server)

            assertEquals(true, repository.setPinned(session, "1", true).getOrThrow().pinned)
            repository.setPinned(session, "1", false).getOrThrow()

            assertEquals("/api/v1/statuses/1/pin", server.takeRequest().path)
            assertEquals("/api/v1/statuses/1/unpin", server.takeRequest().path)
        }
    }

    private fun testSession(server: MockWebServer) = AccountSession(
        sessionId = "session",
        instanceUrl = server.url("/").toString().removeSuffix("/"),
        accountId = "me",
        username = "me",
        displayName = "Me",
        avatarUrl = "",
        accessToken = "token",
    )

    private fun basicStatusJson(id: String) =
        """{"id":"$id","created_at":"2026-09-08T00:00:00Z","account":{"id":"a","username":"alice","acct":"alice"},"content":"hello"}"""
}
