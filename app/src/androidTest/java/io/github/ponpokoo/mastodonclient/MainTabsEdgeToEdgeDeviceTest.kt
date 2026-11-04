package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.abs

@RunWith(Parameterized::class)
class MainTabsEdgeToEdgeDeviceTest(private val dark: Boolean) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf(false), arrayOf(true))
    }

    @Test fun reverseScrollRevealsBarAndHomeStateSurvivesAnotherTab() = withHome {
        val expanded = topBar().fetchSemanticsNode().boundsInRoot
        val navigation = nav().fetchSemanticsNode().boundsInRoot
        savePreview("expanded")
        readOlderPosts()
        rule.waitForIdle()
        val collapsed = topBar().fetchSemanticsNode().boundsInRoot
        assertTrue("Only the status-bar region should remain: $collapsed vs $expanded",
            collapsed.height < expanded.height / 2)
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
        savePreview("collapsed")

        list().performScrollToIndex(15)
        val position = scrollPosition()
        rule.onNodeWithTag("main_tab_notifications").performClick()
        rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("notification_filter_pager")))
            .performTouchInput { swipeUp() }
        rule.onNodeWithTag("main_tab_home").performClick()
        assertEquals(collapsed.height, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(position, scrollPosition(), 1f)

        list().performTouchInput {
            val start = Offset(width * .5f, height * .25f)
            swipe(start, start + Offset(0f, expanded.height * 1.5f), durationMillis = 600)
        }
        rule.waitForIdle()
        assertEquals(expanded.height, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertTrue("Reveal must not require returning to the first post", scrollPosition() > 1f)
        rule.onNodeWithTag("feed_selector").performClick()
        rule.onNodeWithText("連合", useUnmergedTree = true).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()

        readOlderPosts()
        rule.onNodeWithTag("main_tab_home").performClick()
        rule.waitForIdle()
        assertEquals(expanded.height, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(0f, scrollPosition(), 1f)
        rule.onNodeWithText("本文 0", substring = true).assertIsDisplayed()
    }

    @Test fun newItemsNoticeFollowsBarAndRevealsItWhenReturningToLatest() = withHome { models ->
        val expanded = topBar().fetchSemanticsNode().boundsInRoot.height
        list().performScrollToIndex(15)
        readOlderPosts()
        val collapsed = topBar().fetchSemanticsNode().boundsInRoot.height
        rule.runOnIdle {
            val incoming = models.timeline.uiState.value.statuses.first().copy(timelineId = "incoming", statusId = "incoming")
            models.main.viewModelScope.launch {
                models.main.browsing.publish(models.main.browsing.snapshot.value,
                    BrowsingSession.Change.Stream(TimelineStreamEvent.StatusAdded(incoming)))
            }
        }
        val notice = rule.onNodeWithText("↑ 新着1件")
        notice.assertIsDisplayed()
        val hiddenBarNoticeTop = notice.fetchSemanticsNode().boundsInRoot.top
        list().performTouchInput {
            val start = Offset(width * .25f, height * .4f)
            swipe(start, start + Offset(0f, expanded * 1.5f), 600)
        }
        rule.waitForIdle()
        assertEquals(expanded - collapsed,
            notice.fetchSemanticsNode().boundsInRoot.top - hiddenBarNoticeTop, 2f)
        readOlderPosts()
        notice.performClick()
        rule.waitForIdle()
        assertEquals(expanded, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(0f, scrollPosition(), 1f)
    }

    @Test fun translucentFooterShowsScrollingPostsAndLastPostAndFabRemainReachable() = withHome {
        val navigation = nav().fetchSemanticsNode().boundsInRoot
        assertTrue("Home viewport must extend behind the navigation bar",
            list().fetchSemanticsNode().boundsInRoot.bottom > navigation.top)
        list().performScrollToIndex(10)
        val before = nav().captureToImage().toPixelMap()
        readOlderPosts()
        val after = nav().captureToImage().toPixelMap()
        // No tab was pressed: fixed icons and labels stay still while posts move behind them.
        var changed = 0
        for (y in 1 until before.height - 1) for (x in 8 until before.width - 8) {
            val a = before[x, y]
            val b = after[x, y]
            if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > .015f) changed++
        }
        assertTrue("Posts should be visible through the fixed footer, changed=$changed", changed > 8)
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
        savePreview("translucent")

        list().performScrollToIndex(39)
        readOlderPosts()
        rule.waitForIdle()
        val last = rule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().last().boundsInRoot
        assertTrue("The complete final post and its actions must clear the footer: $last vs $navigation",
            last.bottom <= navigation.top + 1f)
        val fab = rule.onNodeWithContentDescription("新規投稿").fetchSemanticsNode().boundsInRoot
        assertTrue("Compose FAB must stay above the footer", fab.bottom <= navigation.top)
        savePreview("end")
    }

    private fun list() = rule.onNodeWithTag("timeline_list")
    @Test fun otherTabsKeepTheirInnerTabsAndRestoreTheirOwnScrollAndHeaderState() = withHome {
        data class Tab(val name: String, val list: String, val header: String, val retainedTab: String)
        val tabs = listOf(
            Tab("explore", "explore_list_posts", "main_search_header", "explore_tab_posts"),
            Tab("notifications", "notifications_list_all", "main_top_bar_notifications", "notification_filter_all"),
            Tab("profile", "profile_scroll", "main_top_bar_profile", "profile_status_tab_posts"),
        )
        val heights = mutableMapOf<String, Float>()
        val positions = mutableMapOf<String, Float>()
        val expandedHeights = mutableMapOf<String, Float>()
        val footer = nav().fetchSemanticsNode().boundsInRoot
        for (tab in tabs) {
            rule.onNodeWithTag("main_tab_${tab.name}").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag(tab.list).fetchSemanticsNodes().isNotEmpty() }
            val header = rule.onNodeWithTag(tab.header)
            val content = rule.onNodeWithTag(tab.list)
            val expanded = header.fetchSemanticsNode().boundsInRoot.height
            expandedHeights[tab.name] = expanded
            repeat(if (tab.name == "profile") 3 else 1) { readOlderPosts(content) }
            val collapsed = header.fetchSemanticsNode().boundsInRoot.height
            assertTrue("${tab.name} header must retreat: $collapsed vs $expanded", collapsed < expanded / 2)
            rule.onNodeWithTag(tab.retainedTab).assertIsDisplayed()
            assertTrue("${tab.name} content should continue behind the footer",
                content.fetchSemanticsNode().boundsInRoot.bottom > footer.top)
            assertEquals(footer, nav().fetchSemanticsNode().boundsInRoot)
            heights[tab.name] = collapsed
            positions[tab.name] = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            savePreview("${tab.name}-collapsed")
        }
        rule.onNodeWithTag("main_tab_home").performClick()
        for (tab in tabs) {
            rule.onNodeWithTag("main_tab_${tab.name}").performClick()
            val content = rule.onNodeWithTag(tab.list)
            assertEquals("${tab.name} header should be restored", heights.getValue(tab.name),
                rule.onNodeWithTag(tab.header).fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals("${tab.name} reading position should be restored", positions.getValue(tab.name),
                content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            rule.onNodeWithTag("main_tab_${tab.name}").performClick()
            rule.waitForIdle()
            assertEquals(expandedHeights.getValue(tab.name), rule.onNodeWithTag(tab.header).fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals(0f, content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
            if (tab.name == "notifications") {
                rule.onNodeWithTag("notification_display_settings").performClick()
                rule.onNodeWithTag("notification_group_toggle").assertIsDisplayed()
                assertEquals(expandedHeights.getValue(tab.name), rule.onNodeWithTag(tab.header).fetchSemanticsNode().boundsInRoot.height, 1f)
                androidx.test.espresso.Espresso.pressBack()
            }
        }
    }

    @Test fun editingKeepsSearchHeaderVisibleAndSubmittingAllowsItToRetreat() = withHome {
        rule.onNodeWithTag("main_tab_explore").performClick()
        val input = rule.onNodeWithTag("search_input")
        input.performClick().performTextInput("散歩")
        input.performImeAction()
        val results = rule.onNodeWithTag("search_results_posts")
        results.assertIsDisplayed()
        input.performClick().assertIsFocused()
        waitForKeyboard(true)
        val header = rule.onNodeWithTag("main_search_header")
        val expanded = header.fetchSemanticsNode().boundsInRoot.height
        results.performTouchInput {
            swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .05f), 600)
        }
        assertEquals("Input must remain visible while editing", expanded, header.fetchSemanticsNode().boundsInRoot.height, 1f)
        input.performImeAction()
        waitForKeyboard(false)
        readOlderPosts(results)
        assertTrue(header.fetchSemanticsNode().boundsInRoot.height < expanded / 2)
        rule.onNodeWithTag("search_target_posts").assertIsDisplayed()
    }

    @Test fun profileLastPostCanMoveAboveTheFixedFooter() = withHome {
        rule.onNodeWithTag("main_tab_profile").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("profile_scroll").fetchSemanticsNodes().isNotEmpty() }
        val content = rule.onNodeWithTag("profile_scroll")
        readOlderPosts(content)
        content.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
        rule.waitForIdle()
        val last = rule.onNodeWithText("本文 39", substring = true)
        last.assertIsDisplayed()
        val lastPost = rule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().last().boundsInRoot
        assertTrue("Last profile post and its actions must clear the footer",
            lastPost.bottom <= nav().fetchSemanticsNode().boundsInRoot.top + 1f)
        savePreview("profile-end")
    }

    private fun waitForKeyboard(visible: Boolean) {
        rule.waitUntil(5_000) {
            var matches = false
            rule.runOnUiThread {
                matches = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            matches
        }
    }

    private fun readOlderPosts(target: SemanticsNodeInteraction = list()) = target.performTouchInput {
        // The viewport extends behind the footer, so start above its touch targets.
        swipe(Offset(width * .5f, height * .65f), Offset(width * .5f, height * .25f), 600)
    }
    private fun topBar() = rule.onNodeWithTag("home_top_bar")
    private fun nav() = rule.onNodeWithTag("main_navigation_bar")
    private fun scrollPosition() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun savePreview(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "home-edge-previews")
        directory.mkdirs()
        File(directory, "${if (dark) "dark" else "light"}-$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private data class Models(val main: MainSessionViewModel, val timeline: TimelineViewModel)

    private fun withHome(check: (Models) -> Unit) {
        val session = AccountSession("home-edge", "https://example.test", "me", "me", "自分", "", "")
        val posts = List(40) { index -> TimelineStatus(
            timelineId = "post-$index", statusId = "post-$index", createdAt = "2026-11-04T00:00:00Z",
            author = StatusAuthor("author-$index", "投稿者 $index", "author$index@example.test", ""),
            boostedBy = null, contentHtml = "<p>本文 $index</p><p>散歩の途中で見つけた景色について、ゆっくり読める投稿です。</p>",
            spoilerText = "", sensitive = false, visibility = "public", url = null,
            repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
        ) }
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(posts, null, true))
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) =
                Result.success(ExplorePage(statuses = posts))
            override suspend fun getProfile(session: AccountSession, accountId: String) = Result.success(UserProfile(
                author = StatusAuthor("me", "自分", "me@example.test", ""), headerUrl = "", noteHtml = "<p>プロフィール紹介</p>",
                followersCount = 10, followingCount = 20, statusesCount = 40, statuses = posts,
                url = "https://example.test/@me", endReached = true, isOwnProfile = true,
            ))
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
                Result.success(TimelinePage(posts, null, true))
            override suspend fun getFeaturedTags(session: AccountSession, accountId: String) = Result.success(emptyList<FeaturedTag>())
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
                Result.success(SearchPage(SearchResults(emptyList(), posts, emptyList()), null, true))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(List(40) { i -> TimelineNotification("notice-$i", "follow",
                    "2026-11-04T00:00:00Z", posts[i].author, null) }, null, true))
        }
        val auth = object : AuthRepository {
            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
            override suspend fun restoreSession() = session
            override suspend fun getSessions() = listOf(session)
            override suspend fun logout() = Unit
        }
        val store = ViewModelStore()
        lateinit var main: MainSessionViewModel
        lateinit var timeline: TimelineViewModel
        lateinit var search: SearchViewModel
        lateinit var notifications: NotificationsViewModel
        lateinit var profile: ProfileViewModel
        lateinit var actions: StatusActionsViewModel
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.runOnIdle {
            main = MainSessionViewModel(auth, repository).also { store.put("main", it) }
            timeline = TimelineViewModel(repository, main.browsing).also { store.put("timeline", it) }
            search = SearchViewModel(repository, main.browsing).also { store.put("search", it) }
            notifications = NotificationsViewModel(repository, main.browsing).also { store.put("notifications", it) }
            profile = ProfileViewModel(repository, main.browsing).also { store.put("profile", it) }
            actions = StatusActionsViewModel(repository, main.browsing).also { store.put("actions", it) }
        }
        try {
            rule.waitUntil(5_000) { timeline.uiState.value.statuses.isNotEmpty() }
            rule.setContent {
                MastodonClientTheme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
                    HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                        fabPositionStore = object : FabPositionStore {
                            override val fabPosition = flowOf<FabPosition?>(null)
                            override suspend fun setFabPosition(position: FabPosition) = Unit
                            override suspend fun resetFabPosition() = Unit
                        }, onLoggedOut = {}, onStatusClick = {}, onCompose = {}, onQuote = { _, _ -> },
                        onOpenLink = {}, openLinksInApp = true, onOpenLinksInAppChange = {},
                        onAccountClick = {}, onMediaClick = { _, _ -> }, onSettings = {}, onFollowers = {},
                        onFollowing = {}, onEditStatus = {}, onOpenLists = {}, onOpenBookmarks = {}, onOpenFavourites = {})
                }
            }
            rule.onNodeWithTag("timeline_list").assertIsDisplayed()
            check(Models(main, timeline))
        } finally {
            rule.runOnIdle { store.clear() }
        }
    }
}
