package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailScreen
import io.github.ponpokoo.mastodonclient.feature.detail.StatusDetailViewModel
import io.github.ponpokoo.mastodonclient.navigation.ScreenNavHost
import io.github.ponpokoo.mastodonclient.navigation.animatedScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Slow responses must not expose the cached post before its final initial position. */
class ConversationLoadingPositionDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val store = ViewModelStore()
    private val account = AccountSession("session", "https://example.test", "me", "me", "Me", "", "test-token")
    private val selected = post("selected")
    private var detailRequests = 0

    @After fun clear() { rule.runOnIdle { store.clear() } }

    private fun post(id: String) = TimelineStatus(
        timelineId = id, statusId = id, createdAt = "2026-11-06T00:00:00Z",
        author = StatusAuthor("author-$id", "Author $id", "$id@example.test", ""),
        boostedBy = null, contentHtml = "<p>$id body</p>", spoilerText = "",
        sensitive = false, visibility = "public", url = null,
        repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
    )

    private fun show(cached: Boolean, reuseConversation: Boolean = false, authenticationReady: CompletableDeferred<Unit>? = null,
        cachedConversationAvailable: Boolean = reuseConversation,
        cachedStatus: TimelineStatus = selected.copy(repliesCount = 1),
    ): Channel<Result<StatusDetail>> {
        val response = Channel<Result<StatusDetail>>(Channel.UNLIMITED)
        val repository = object : TimelineRepository {
            override fun getCachedStatus(session: AccountSession, statusId: String) = cachedStatus.takeIf { cached }
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(emptyList(), null, true))
            override suspend fun getStatusDetail(session: AccountSession, statusId: String): Result<StatusDetail> {
                detailRequests++
                return response.receive()
            }
            override fun getCachedConversation(session: AccountSession, sourceStatusId: String, statusId: String) =
                StatusDetail(selected, (0 until 8).map { post("before-$it") }, (0 until 8).map { post("after-$it") })
                    .takeIf { cachedConversationAvailable }
        }
        val auth = object : AuthRepository {
            override suspend fun restoreSession(): AccountSession {
                authenticationReady?.await()
                return account
            }
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(account)
            override suspend fun logout() = Unit
        }
        lateinit var model: StatusDetailViewModel
        rule.runOnIdle {
            model = StatusDetailViewModel("selected", repository, auth, conversationSourceId = "source".takeIf { reuseConversation })
            store.put("detail", model)
        }
        rule.setContent { MaterialTheme {
            val navigation = rememberNavController()
            ScreenNavHost(navigation, "source") {
                animatedScreen("source") {
                    TextButton(onClick = { navigation.navigate("detail") }) { Text("Open conversation") }
                }
                animatedScreen("detail") {
                    StatusDetailScreen(model, AppPreferences(), { navigation.popBackStack() }, {}, { _, _ -> }, {}, {},
                        { navigation.navigate("profile") }, { _, _ -> }, {})
                }
                animatedScreen("profile") {
                    TextButton(onClick = { navigation.popBackStack() }) { Text("Return to conversation") }
                }
            }
        } }
        rule.onNodeWithText("Open conversation").performClick()
        rule.waitForIdle()
        return response
    }

    private fun target() = rule.onNode(
        hasTestTag("timeline_status") and hasAnyDescendant(hasText("Author selected")),
    )

    private fun targetTop(): Float {
        rule.waitForIdle()
        return target().fetchSemanticsNode().boundsInRoot.top
    }

    private fun complete(response: Channel<Result<StatusDetail>>, replies: Int = 8) {
        rule.runOnIdle { response.trySend(Result.success(StatusDetail(
            selected, (0 until 8).map { post("before-$it") }, (0 until replies).map { post("after-$it") },
        ))) }
        rule.waitForIdle()
    }

    private fun assertNearCenter() {
        val viewport = rule.onNodeWithTag("status_detail").fetchSemanticsNode().boundsInRoot
        val card = target().fetchSemanticsNode().boundsInRoot
        // The centered lazy row also contains outer padding and the metadata divider.
        assertEquals(viewport.center.y, card.center.y, viewport.height * 0.05f)
    }

    private fun assertLoading() {
        rule.onNodeWithTag("status_detail_loading").assertIsDisplayed()
        target().assertDoesNotExist()
    }

    @Test fun cachedPostIsHiddenUntilPositionedAndFirstVisibleFramesAreCentered() {
        val response = show(true)
        assertLoading()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { response.trySend(Result.success(StatusDetail(
            selected, (0 until 8).map { post("before-$it") }, (0 until 8).map { post("after-$it") },
        ))) }
        var visibleFrames = 0
        repeat(8) {
            rule.mainClock.advanceTimeByFrame()
            if (target().isDisplayed()) {
                visibleFrames++
                assertNearCenter()
                rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("Conversation should become visible", visibleFrames > 0)
        assertNearCenter()
    }

    @Test fun noCachedPostShowsLoadingThenCenteredConversation() {
        val response = show(false)
        assertLoading()
        complete(response)
        assertNearCenter()
    }

    @Test fun cachedStandalonePostOpensDuringFetchAndKeepsItsPositionWhenAReplyArrives() {
        val response = show(cached = true, cachedStatus = selected)
        rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
        target().assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, detailRequests) }
        val before = targetTop()
        rule.runOnIdle {
            response.trySend(Result.success(StatusDetail(
                selected.copy(repliesCount = 1),
                emptyList(),
                listOf(post("new-reply").copy(inReplyToId = selected.statusId)),
            )))
        }
        assertEquals(before, targetTop(), 3f)
        rule.onNodeWithTag("status_detail").performScrollToNode(hasText("Author new-reply"))
        rule.onNodeWithText("Author new-reply").assertIsDisplayed()
    }

    @Test fun cachedReplyWithNoRepliesWaitsForItsParentBeforeDisplaying() {
        val response = show(cached = true, cachedStatus = selected.copy(inReplyToId = "before-7"))
        assertLoading()
        complete(response)
        assertNearCenter()
    }

    @Test fun cachedConversationOpensCenteredDuringFetchAndRetainsPositionWhenPostsAreInsertedAbove() {
        val authenticationReady = CompletableDeferred<Unit>()
        val response = show(cached = true, reuseConversation = true, authenticationReady = authenticationReady)
        rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
        target().assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { authenticationReady.complete(Unit) }
        var visibleFrames = 0
        repeat(12) {
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
            if (target().isDisplayed()) {
                visibleFrames++
                assertNearCenter()
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("Cached conversation must appear after positioning", visibleFrames > 0)
        assertNearCenter()
        rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, detailRequests) }
        rule.onNodeWithTag("status_detail").performTouchInput {
            swipeDown(startY = height * 0.5f, endY = height * 0.6f, durationMillis = 1_000)
        }
        val before = targetTop()
        rule.runOnIdle {
            response.trySend(Result.success(StatusDetail(
                selected,
                listOf(post("added-ancestor")) + (0 until 8).map { post("before-$it") },
                (0 until 9).map { post("after-$it") },
            )))
        }
        assertEquals(before, targetTop(), 3f)
        rule.onNodeWithTag("status_detail").performScrollToNode(hasText("Author after-8"))
        rule.onNodeWithText("Author after-8").assertIsDisplayed()

        rule.onNodeWithTag("status_detail").performScrollToNode(hasText("Author selected"))
        val beforeManualRefresh = targetTop()
        rule.onNodeWithContentDescription("会話を更新").performClick()
        rule.runOnIdle { assertEquals(2, detailRequests) }
        assertEquals(beforeManualRefresh, targetTop(), 3f)
    }

    @Test fun homeEntryReusesCachedConversationWithoutLoadingIndicator() {
        val authenticationReady = CompletableDeferred<Unit>()
        show(cached = true, authenticationReady = authenticationReady, cachedConversationAvailable = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
        rule.runOnIdle { authenticationReady.complete(Unit) }
        var visibleFrames = 0
        repeat(12) {
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("status_detail_loading").assertDoesNotExist()
            if (target().isDisplayed()) {
                visibleFrames++
                assertNearCenter()
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(visibleFrames > 0)
        rule.runOnIdle { assertEquals(1, detailRequests) }
    }

    @Test fun lastPostIsConstrainedByEndOfConversation() {
        val response = show(true)
        assertLoading()
        complete(response, replies = 0)
        val viewport = rule.onNodeWithTag("status_detail").fetchSemanticsNode().boundsInRoot
        assertTrue(target().fetchSemanticsNode().boundsInRoot.center.y > viewport.center.y + 20)
    }

    @Test fun draggingWhileLoadingDoesNotSuppressInitialCentering() {
        val response = show(true)
        assertLoading()
        rule.onNodeWithTag("status_detail_loading").performTouchInput { swipeUp() }
        complete(response)
        assertNearCenter()
    }

    @Test fun returningFromAnotherScreenPreservesManualPosition() {
        val response = show(true)
        complete(response)
        rule.onNodeWithTag("status_detail").performTouchInput {
            swipeDown(startY = height * 0.5f, endY = height * 0.6f, durationMillis = 1_000)
        }
        val before = targetTop()
        rule.onNodeWithContentDescription("Author selectedのプロフィール画像").performClick()
        rule.onNodeWithText("Return to conversation").performClick()
        val after = targetTop()
        assertEquals(before, after, 3f)
    }

    @Test fun failedInitialLoadWithCacheOffersRetryWithoutShowingUnpositionedPost() {
        assertFailureAndRetry(cached = true)
    }

    @Test fun failedInitialLoadWithoutCacheOffersRetry() {
        assertFailureAndRetry(cached = false)
    }

    private fun assertFailureAndRetry(cached: Boolean) {
        val response = show(cached)
        rule.runOnIdle { response.trySend(Result.failure(IllegalStateException("offline"))) }
        rule.onNodeWithText("offline").assertIsDisplayed()
        target().assertDoesNotExist()
        rule.onNodeWithText("再試行").performClick()
        assertLoading()
        complete(response)
        assertNearCenter()
    }
}
