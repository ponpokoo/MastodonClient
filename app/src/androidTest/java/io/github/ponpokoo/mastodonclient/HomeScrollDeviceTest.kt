package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.OwnProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScrollDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeTapReachesTopFromDifferentDistancesWithVariableHeightPosts() = withHomeTimeline { _, _, _ ->
        for (index in listOf(140, 35, 5)) {
            rule.onNodeWithTag("timeline_list").performScrollToIndex(index)
            assertTrue(scrollPosition() > 0f)
            rule.onNodeWithTag("main_tab_home").performClick()
            rule.waitForIdle()
            assertEquals("Home tap from item $index stopped early", 0f, scrollPosition(), 0f)
        }
    }

    @Test fun homeTapReachesNewTopWhenPostsArriveDuringAnimation() = withHomeTimeline { main, timeline, statuses ->
        rule.onNodeWithTag("timeline_list").performScrollToIndex(140)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("main_tab_home").performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.runOnUiThread {
                main.viewModelScope.launch {
                    main.browsing.publish(main.browsing.snapshot.value, BrowsingSession.Change.Stream(
                        TimelineStreamEvent.StatusAdded(statuses.first().copy(timelineId = "new", statusId = "new")),
                    ))
                }
            }
            rule.mainClock.advanceTimeBy(8_000)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("new", timeline.uiState.value.statuses.first().timelineId) }
        assertEquals(0f, scrollPosition(), 0f)
    }

    @Test fun swipingDuringHomeAnimationKeepsTheUsersNewPosition() = withHomeTimeline { _, _, _ ->
        rule.onNodeWithTag("timeline_list").performScrollToIndex(140)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("main_tab_home").performClick()
            rule.mainClock.advanceTimeBy(64)
            rule.onNodeWithTag("timeline_list").performTouchInput { swipeUp() }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        val position = scrollPosition()
        assertTrue(position > 0f)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(position, scrollPosition(), 0f)
    }

    private fun scrollPosition() = rule.onNodeWithTag("timeline_list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun withHomeTimeline(block: (MainSessionViewModel, TimelineViewModel, List<TimelineStatus>) -> Unit) {
        val session = AccountSession("test", "https://example.test", "me", "me", "Me", "", "test-token")
        val statuses = (0 until 180).map { index ->
            TimelineStatus(
                timelineId = "post-$index", statusId = "post-$index", createdAt = "2026-10-01T00:00:00Z",
                author = StatusAuthor("alice", "Alice", "alice@example.test", ""), boostedBy = null,
                contentHtml = "<p>Post $index</p>" + (0 until index % 9).joinToString("") { "<p>Additional line of text</p>" },
                spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
            )
        }
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(statuses, null, true))
        }
        val auth = object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
            override suspend fun restoreSession() = session
            override suspend fun getSessions() = listOf(session)
            override suspend fun logout() = Unit
        }
        val models = mutableListOf<ViewModel>()
        lateinit var main: MainSessionViewModel
        lateinit var timeline: TimelineViewModel
        lateinit var search: SearchViewModel
        lateinit var notifications: NotificationsViewModel
        lateinit var profile: OwnProfileViewModel
        lateinit var actions: StatusActionsViewModel
        rule.runOnIdle {
            main = MainSessionViewModel(auth, repository).also(models::add)
            timeline = TimelineViewModel(repository, main.browsing).also(models::add)
            search = SearchViewModel(repository, main.browsing).also(models::add)
            notifications = NotificationsViewModel(repository, main.browsing).also(models::add)
            profile = OwnProfileViewModel(repository, main.browsing).also(models::add)
            actions = StatusActionsViewModel(repository, main.browsing).also(models::add)
        }
        try {
            rule.setContent {
                MaterialTheme {
                    HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                        onLoggedOut = {}, onStatusClick = {}, onCompose = {}, onQuote = { _, _ -> },
                        onOpenLink = {}, openLinksInApp = true, onOpenLinksInAppChange = {},
                        onAccountClick = {}, onMediaClick = { _, _ -> }, onSettings = {}, onFollowers = {},
                        onFollowing = {}, onEditStatus = {}, onOpenLists = {}, onOpenBookmarks = {}, onOpenFavourites = {})
                }
            }
            rule.waitUntil(5_000) { !timeline.uiState.value.isInitialLoading }
            block(main, timeline, statuses)
        } finally {
            rule.runOnIdle { models.forEach { it.viewModelScope.cancel() } }
        }
    }
}
