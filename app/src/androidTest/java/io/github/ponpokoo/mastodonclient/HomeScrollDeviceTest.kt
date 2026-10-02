package io.github.ponpokoo.mastodonclient

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import io.github.ponpokoo.mastodonclient.feature.timeline.StatusCard
import io.github.ponpokoo.mastodonclient.feature.timeline.animateToTimelineTop
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
            rule.mainClock.autoAdvance = false
            try {
                rule.onNodeWithTag("main_tab_home").performClick()
                rule.mainClock.advanceTimeBy(600)
                assertEquals("Home tap from item $index took too long", 0f, scrollPosition(), 0f)
            } finally {
                rule.mainClock.autoAdvance = true
            }
        }
    }

    @Test fun homeTapPassesUrlsAndReachesTop() = checkScrollPast(PostContent.Url)

    @Test fun homeTapPassesImageThumbnailsAndReachesTop() = checkScrollPast(PostContent.Media)

    @Test fun homeTapPassesLinkThumbnailsAndReachesTop() = checkScrollPast(PostContent.UrlPreview)

    @Test fun homeTapPassesUrlsImagesAndLinkThumbnailsTogether() = checkScrollPast(PostContent.UrlMediaPreview)

    @Test fun notificationTapAlsoReturnsToTopWithin600Milliseconds() = withHomeTimeline { _, _, _ ->
        rule.onNodeWithTag("main_tab_notifications").performClick()
        rule.waitForIdle()
        val list = rule.onAllNodes(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyAncestor(hasTestTag("notifications_screen")))[0]
        for (index in listOf(140, 35, 5)) {
            list.performScrollToIndex(index)
            rule.mainClock.autoAdvance = false
            try {
                rule.onNodeWithTag("main_tab_notifications").performClick()
                rule.mainClock.advanceTimeBy(600)
                assertEquals("Notification tap from item $index took too long", 0f,
                    list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
            } finally {
                rule.mainClock.autoAdvance = true
            }
        }
    }

    @Test fun shortReturnDoesNotJumpAwayFromTopBesideATallRow() {
        lateinit var list: LazyListState
        rule.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = 1, initialFirstVisibleItemScrollOffset = 20)
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        items((0 until 10).toList()) { index ->
                            Text("Row $index", modifier = Modifier.height(if (index == 1) 2_400.dp else 80.dp))
                        }
                    }
                    Button(onClick = { scope.launch { list.animateToTimelineTop() } }) { Text("Go to top") }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText("Go to top").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.runOnUiThread {
                assertTrue("Short return jumped away from the top",
                    list.firstVisibleItemIndex < 1 ||
                        (list.firstVisibleItemIndex == 1 && list.firstVisibleItemScrollOffset <= 20))
            }
            rule.mainClock.advanceTimeBy(600)
            rule.runOnUiThread {
                assertEquals(0, list.firstVisibleItemIndex)
                assertEquals(0, list.firstVisibleItemScrollOffset)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun scrollDoesNotSlowToACrawlBeforePassingATallLinkPost() {
        val image = "android.resource://io.github.ponpokoo.mastodonclient/drawable/nagisa_launcher_art"
        val statuses = (0 until 20).map { index ->
            TimelineStatus(
                timelineId = "$index", statusId = "$index", createdAt = "2026-10-02T00:00:00Z",
                author = StatusAuthor("alice", "Alice", "alice@example.test", ""), boostedBy = null,
                contentHtml = "<p>Post $index</p>" + if (index == 3) {
                    "<p><a href=\"https://example.test/article\">https://example.test/article</a></p>" +
                        "<p>Article description</p>".repeat(25)
                } else "",
                spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0,
                mediaAttachments = if (index == 3) listOf(MediaAttachment("image", "image", image, image, null)) else emptyList(),
                previewCard = if (index == 3) PreviewCard("https://example.test/article", "Article", "Description", "link", "", image, 1f) else null,
            )
        }
        lateinit var list: LazyListState
        rule.setContent {
            list = rememberLazyListState(initialFirstVisibleItemIndex = 14)
            val scope = rememberCoroutineScope()
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        items(statuses, key = { it.timelineId }) { status ->
                            StatusCard(status, onStatusClick = null, onUnavailableAction = {})
                        }
                    }
                    Button(onClick = { scope.launch { list.animateToTimelineTop() } }) { Text("Go to top") }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        var slowFrames = 0
        var longestSlowRun = 0
        var previous: Pair<Int, Int>? = null
        try {
            rule.onNodeWithText("Go to top").performClick()
            repeat(500) {
                rule.mainClock.advanceTimeByFrame()
                rule.runOnUiThread {
                    val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                    // Near the destination, easing is expected. Before it is visible,
                    // several nearly stationary frames make the motion appear to stop.
                    val slow = previous?.let { before ->
                        position.first > 0 && position.first == before.first &&
                            kotlin.math.abs(position.second - before.second) < 4
                    } ?: false
                    slowFrames = if (slow) slowFrames + 1 else 0
                    longestSlowRun = maxOf(longestSlowRun, slowFrames)
                    previous = position
                }
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        assertTrue("Motion slowed before reaching the top for $longestSlowRun frames", longestSlowRun < 6)
        rule.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        }
    }

    private fun checkScrollPast(content: PostContent) = withHomeTimeline(content) { _, _, _ ->
        // Visit the same cards twice as well as testing long and short scrolls.
        for (index in listOf(140, 35, 35, 5)) {
            rule.onNodeWithTag("timeline_list").performScrollToIndex(index)
            rule.onNodeWithTag("main_tab_home").performClick()
            rule.waitForIdle()
            assertEquals("Home tap stopped while passing $content", 0f, scrollPosition(), 0f)
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

    private enum class PostContent { Plain, Url, Media, UrlPreview, UrlMediaPreview }

    private fun withHomeTimeline(content: PostContent = PostContent.Plain, block: (MainSessionViewModel, TimelineViewModel, List<TimelineStatus>) -> Unit) {
        val session = AccountSession("test", "https://example.test", "me", "me", "Me", "", "test-token")
        val imageUrl = "android.resource://io.github.ponpokoo.mastodonclient/drawable/nagisa_launcher_art"
        val statuses = (0 until 180).map { index ->
            val articleUrl = "https://example.test/article/$index"
            val sample = index % 3 == 1
            val hasUrl = sample && content in setOf(PostContent.Url, PostContent.UrlPreview, PostContent.UrlMediaPreview)
            val hasMedia = sample && content in setOf(PostContent.Media, PostContent.UrlMediaPreview)
            val hasPreview = sample && content in setOf(PostContent.UrlPreview, PostContent.UrlMediaPreview)
            TimelineStatus(
                timelineId = "post-$index", statusId = "post-$index", createdAt = "2026-10-01T00:00:00Z",
                author = StatusAuthor("alice", "Alice", "alice@example.test", ""), boostedBy = null,
                contentHtml = "<p>Post $index</p>" +
                    (0 until index % 9).joinToString("") { "<p>Additional line of text</p>" } +
                    if (hasUrl) "<p><a href=\"$articleUrl\">$articleUrl</a></p>" else "",
                spoilerText = "", sensitive = false, visibility = "public", url = null,
                repliesCount = 0, boostsCount = 0, favouritesCount = 0,
                mediaAttachments = if (hasMedia) listOf(MediaAttachment(
                    id = "image-$index", type = "image", url = imageUrl, previewUrl = imageUrl, description = "Attached image",
                )) else emptyList(),
                previewCard = if (hasPreview) PreviewCard(
                    url = articleUrl, title = "Article $index with a long title on two lines",
                    description = "An article description that occupies more than one line.", type = "link",
                    byline = "Example", imageUrl = imageUrl,
                    aspectRatio = 1f,
                ) else null,
            )
        }
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(statuses, null, true))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(statuses.map { status ->
                    TimelineNotification(status.statusId, "favourite", status.createdAt, status.author, status)
                }, null, true))
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
