package io.github.ponpokoo.mastodonclient

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.SavedStateHandle

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.espresso.Espresso.pressBack
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ponpokoo.mastodonclient.core.preferences.FabPosition
import io.github.ponpokoo.mastodonclient.core.preferences.FabPositionStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.AuthRepository
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.main.MainSessionViewModel
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsViewModel
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.feature.search.SearchViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsMaintenanceViewModel
import io.github.ponpokoo.mastodonclient.feature.settings.SettingsScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.HomeTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.timeline.TimelineViewModel
import io.github.ponpokoo.mastodonclient.navigation.Route
import io.github.ponpokoo.mastodonclient.navigation.ScreenNavHost
import io.github.ponpokoo.mastodonclient.navigation.animatedScreen
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenRequest
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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

    @Test fun backRestoresSavedTabHistoryAndHomePosition() {
        val restoration = StateRestorationTester(rule)
        withHome(restoration = restoration) {
            list().performScrollToIndex(12)
            val position = scrollPosition()
            rule.onNodeWithTag("main_tab_notifications").performClick()
            rule.onNodeWithTag("main_tab_profile").performClick()
            rule.onNodeWithTag("main_tab_profile").performClick()

            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithTag("main_tab_profile").assertIsSelected()
            pressBack()
            rule.onNodeWithTag("main_tab_notifications").assertIsSelected()
            pressBack()
            rule.onNodeWithTag("main_tab_home").assertIsSelected()
            assertEquals(position, scrollPosition(), 1f)
            assertNoBackCallback()
        }
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun predictiveBackRespectsDetailAndTabHistoryOnCancelAndCommit() = withHome(openDetails = true) {
        rule.onNodeWithTag("main_tab_notifications").performClick()
        rule.onNodeWithTag("notification_status_quote", useUnmergedTree = true).performClick()
        val detail = rule.onNodeWithText("Detail post-0")

        completePredictiveBack(commit = false)
        detail.assertIsDisplayed()
        completePredictiveBack(commit = true)
        detail.assertDoesNotExist()
        rule.onNodeWithTag("main_tab_notifications").assertIsSelected()

        completePredictiveBack(commit = false)
        rule.onNodeWithTag("main_tab_notifications").assertIsSelected()
        completePredictiveBack(commit = true)
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
        assertNoBackCallback()
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun systemBackGestureReturnsToPreviousTab() {
        assumeTrue(Settings.Secure.getInt(rule.activity.contentResolver, "navigation_mode", 0) == 2)
        withHome {
            rule.onNodeWithTag("main_tab_notifications").performClick()
            val screen = rule.runOnIdle { rule.activity.windowManager.currentWindowMetrics.bounds }
            val start = screen.left + 1
            val end = screen.left + screen.width() / 3
            val y = screen.centerY()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input swipe $start $y $end $y 350"),
            ).use { it.readBytes() }
            rule.waitUntil(5_000) {
                rule.onAllNodes(hasTestTag("main_tab_home") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            list().assertIsDisplayed()
            assertNoBackCallback()
        }
    }

    @Test fun accountChangeAndNotificationOpenClearPreviousTabVisits() {
        val request = mutableStateOf<NotificationOpenRequest?>(null)
        withHome(notificationRequest = request) { models ->
            val initialSession = checkNotNull(models.main.uiState.value.session)
            rule.onNodeWithTag("main_tab_explore").performClick()
            rule.runOnIdle { models.main.switchAccount("other-edge") }
            rule.onNodeWithTag("main_tab_home").assertIsSelected()
            assertNoBackCallback()

            rule.onNodeWithTag("main_tab_profile").performClick()
            rule.runOnIdle { request.value = NotificationOpenRequest(initialSession.sessionId, "notice-0") }
            rule.onNodeWithTag("main_tab_notifications").assertIsSelected()
            rule.runOnIdle { assertEquals(initialSession.sessionId, models.main.uiState.value.session?.sessionId) }
            assertNoBackCallback()
        }
    }

    private fun completePredictiveBack(commit: Boolean) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnUiThread {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.6f, BackEventCompat.EDGE_LEFT))
            }
            rule.mainClock.advanceTimeBy(64)
            rule.runOnUiThread {
                if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    private fun assertNoBackCallback() {
        rule.runOnIdle { assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun reverseScrollRevealsBarAndHomeStateSurvivesAnotherTab() = withHome {
        val expanded = topBar().fetchSemanticsNode().boundsInRoot
        val navigation = nav().fetchSemanticsNode().boundsInRoot
        val fab = rule.onNodeWithContentDescription("新規投稿").fetchSemanticsNode().boundsInRoot
        val viewportBottom = list().fetchSemanticsNode().boundsInRoot.bottom
        savePreview("expanded")
        readOlderPosts()
        rule.waitForIdle()
        val collapsed = topBar().fetchSemanticsNode().boundsInRoot
        assertEquals("Only the status-bar region should remain", topInsetPx(), collapsed.height, 1f)
        nav().assertIsDisplayed()
        assertEquals("The footer must stay fixed while the header collapses", navigation,
            nav().fetchSemanticsNode().boundsInRoot)
        assertEquals(fab, rule.onNodeWithContentDescription("新規投稿").fetchSemanticsNode().boundsInRoot)
        assertEquals(viewportBottom, list().fetchSemanticsNode().boundsInRoot.bottom, 1f)
        savePreview("collapsed")

        val collapsedPosition = scrollPosition()
        rule.onNodeWithTag("main_tab_notifications").performClick()
        rule.onNodeWithTag("main_tab_home").performClick()
        assertEquals("Fixed navigation must work while the header is collapsed", collapsed.height,
            topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(collapsedPosition, scrollPosition(), 1f)

        list().performScrollToIndex(15)
        revealNavigation()
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
        assertTrue("Reveal must not require returning to the first post", scrollPosition() > 1f)
        val position = scrollPosition()
        rule.onNodeWithTag("main_tab_notifications").performClick()
        val notifications = rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("notification_filter_pager")))
        readOlderPosts(notifications)
        nav().assertIsDisplayed()
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
        revealNavigation(notifications)
        rule.onNodeWithTag("main_tab_home").performClick()
        assertEquals(expanded.height, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(position, scrollPosition(), 1f)

        assertEquals(expanded.height, topBar().fetchSemanticsNode().boundsInRoot.height, 1f)
        rule.onNodeWithTag("feed_selector").performClick()
        rule.onNodeWithText("連合", useUnmergedTree = true).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()

        readOlderPosts()
        revealNavigation()
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
        nav().assertIsDisplayed()
    }

    @Test fun translucentFooterShowsScrollingPostsAndLastPostAndFabRemainReachable() = withHome {
        val navigation = nav().fetchSemanticsNode().boundsInRoot
        assertTrue("Home viewport must extend behind the navigation bar",
            list().fetchSemanticsNode().boundsInRoot.bottom > navigation.top)
        list().performScrollToIndex(10)
        val before = nav().captureToImage().toPixelMap()
        // Shift the actual content behind the footer. Other rows at the same offset can
        // look nearly identical because the fixture repeats the same text and actions.
        list().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 48f)) }
        val after = nav().captureToImage().toPixelMap()
        // No tab was pressed: icons and labels stay still while posts move behind them.
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
        rule.waitForIdle()
        val last = rule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().last().boundsInRoot
        assertTrue("The complete final post and its actions must clear the footer: $last vs $navigation",
            last.bottom <= navigation.top + 1f)
        val fab = rule.onNodeWithContentDescription("新規投稿").fetchSemanticsNode().boundsInRoot
        assertTrue("Compose FAB must stay above the footer", fab.bottom <= navigation.top)
        savePreview("end")
    }

    private fun list() = rule.onNodeWithTag("timeline_list")

    @Test fun liquidGlassChoiceUpdatesPreviewAndShowsTheScrollingBackdrop() {
        val preferences = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        withHome(preferences) {
            val floatingBounds = nav().fetchSemanticsNode().boundsInRoot
            measureFooterScroll("Floating")
            rule.onNodeWithContentDescription("設定").performClick()
            rule.onNodeWithTag("settings_category_Appearance").performScrollTo().performClick()
            rule.onNodeWithText("下部タブバー").performClick()
            rule.onNode(hasText("Liquid Glass") and hasAnyAncestor(isPopup())).performClick()
            rule.onNodeWithText("Liquid Glass").assertIsDisplayed()
            val preview = hasTestTag("bottom_tab_bar_preview")
            rule.onNode(hasTestTag("main_navigation_bar") and hasAnyAncestor(preview), useUnmergedTree = true)
                .assertIsDisplayed()
            rule.onAllNodes(hasClickAction() and hasAnyAncestor(preview)).assertCountEquals(0)
            repeat(2) { rule.onNodeWithContentDescription("戻る").performClick() }

            assertEquals(floatingBounds, nav().fetchSemanticsNode().boundsInRoot)
            rule.onNode(hasTestTag("main_tab_notifications") and hasText("2")).assertIsDisplayed()
            list().performScrollToIndex(10)
            val before = nav().captureToImage().toPixelMap()
            list().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 48f)) }
            val after = nav().captureToImage().toPixelMap()
            val backgroundChanged = (8 until before.width - 8).any { x ->
                (1 until before.height - 1).any { y ->
                    val a = before[x, y]
                    val b = after[x, y]
                    abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > .02f
                }
            }
            assertTrue("Scrolling content must change pixels inside the fixed bar", backgroundChanged)
            assertEquals(floatingBounds, nav().fetchSemanticsNode().boundsInRoot)
            savePreview("liquid-glass")
            measureFooterScroll("LiquidGlass")
            rule.onNodeWithTag("main_tab_home").performClick()
            rule.waitUntil(5_000) { scrollPosition() == 0f }
            rule.onNodeWithTag("main_tab_notifications").performClick().assertIsSelected()
        }
    }

    private fun measureFooterScroll(style: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            InstrumentationRegistry.getArguments().getString("measureNavigationGlass") != "true"
        ) return

        val distance = with(rule.density) { 480.dp.toPx() }
        fun scrollPair() {
            for (delta in listOf(distance, -distance)) {
                list().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
                rule.waitForIdle()
            }
        }
        list().performScrollToIndex(10)
        scrollPair()
        list().performScrollToIndex(10)
        val frameTimes = mutableListOf<Long>()
        var missedDeadlines = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
                frameTimes.add(total)
                if (deadline > 0 && total > deadline) missedDeadlines++
            }
        }
        val window = rule.activity.window
        rule.runOnIdle { window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        try {
            repeat(4) { scrollPair() }
        } finally {
            rule.runOnIdle { window.removeOnFrameMetricsAvailableListener(listener) }
        }
        check(frameTimes.isNotEmpty())
        val sorted = frameTimes.sorted()
        val p95Ms = sorted[(sorted.lastIndex * 0.95).toInt()] / 1_000_000.0
        Log.i("NagisaGlassFrames", "style=$style dark=$dark frames=${sorted.size} p95Ms=$p95Ms missedDeadlines=$missedDeadlines")
    }

    @Test fun homeDragAndFlingTravelDoesNotDependOnWhetherTheHeaderStartsVisible() = withHome {
        val viewport = list().fetchSemanticsNode().boundsInRoot
        fun travel(expanded: Boolean, towardOlder: Boolean): Float {
            if (expanded) revealNavigation() else readOlderPosts()
            list().performScrollToIndex(15)
            assertEquals("Collapsing chrome must not resize the scrolling viewport",
                viewport, list().fetchSemanticsNode().boundsInRoot)
            rule.mainClock.autoAdvance = false
            try {
                list().performTouchInput {
                    val start = Offset(width * .5f, height * .5f)
                    swipe(start, start + Offset(0f, if (towardOlder) -240f else 240f), 160)
                }
                rule.mainClock.advanceTimeBy(4_000)
                return scrollPosition()
            } finally { rule.mainClock.autoAdvance = true }
        }
        for (towardOlder in listOf(true, false)) {
            val expandedTravel = travel(true, towardOlder)
            val collapsedTravel = travel(false, towardOlder)
            // LazyList reports integer-pixel positions; allow one pixel of rounding after a fling.
            assertEquals("Header visibility must not consume drag distance or change inertia",
                expandedTravel, collapsedTravel, 1f)
        }
    }

    @Test fun transparentSideGapLetsTheTimelineScrollWithoutMovingTheNavigation() = withHome {
        val navigation = nav().fetchSemanticsNode().boundsInRoot
        val viewport = list().fetchSemanticsNode().boundsInRoot
        assertTrue("The floating bar must leave a visible side gap", navigation.left > viewport.left)
        val before = scrollPosition()
        list().performTouchInput {
            val start = Offset((navigation.left - viewport.left) / 2f, navigation.center.y - viewport.top)
            swipe(start, start - Offset(0f, height * .4f), 600)
        }
        rule.waitForIdle()
        assertTrue("The transparent gap must not consume the timeline gesture", scrollPosition() > before)
        nav().assertIsDisplayed()
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
        revealNavigation()
        assertEquals(navigation, nav().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun fullWidthChoiceCoversBothTimelineEdgesAndKeepsTabActions() {
        val preferences = UserPreferencesStore(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        })
        val openedPosts = mutableListOf<String>()
        withHome(preferences, onStatusClick = openedPosts::add) {
            val floatingBar = nav().fetchSemanticsNode().boundsInRoot
            val viewport = list().fetchSemanticsNode().boundsInRoot
            val windowWidth = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
            val edgeOffset = with(rule.density) { 4.dp.toPx() }
            fun expectedFloatingWidth(availableWidth: Float): Float = with(rule.density) {
                val width = if (windowWidth.toDp() >= 600.dp) availableWidth * 0.9f else 352.dp.toPx()
                minOf(width, availableWidth - 2 * 16.dp.roundToPx())
            }
            assertTrue(floatingBar.left > viewport.left + edgeOffset)
            assertTrue(floatingBar.right < viewport.right - edgeOffset)
            assertEquals(expectedFloatingWidth(viewport.width), floatingBar.width, 1f)
            val notificationBadge = rule.onNode(hasTestTag("main_tab_notifications") and hasText("2"))
            notificationBadge.assertIsDisplayed()
            list().performScrollToIndex(10)
            val edgePoints = listOf(
                viewport.left + edgeOffset,
                viewport.right - edgeOffset,
            )
            // Hit the top strip of the full-width surface, just above the tab controls.
            val y = floatingBar.top + with(rule.density) { 8.dp.roundToPx() } + 1f
            fun tapEdges(y: Float) {
                list().performTouchInput {
                    edgePoints.forEach { x -> click(Offset(x - viewport.left, y - viewport.top)) }
                }
                rule.waitForIdle()
            }
            tapEdges(y)
            assertEquals("Both taps in the floating bar's gaps should reach the post", 2, openedPosts.size)

            rule.onNodeWithContentDescription("設定").performClick()
            rule.onNodeWithTag("settings_category_Appearance").performScrollTo().performClick()
            val preview = hasTestTag("bottom_tab_bar_preview")
            val previewBar = rule.onNode(hasTestTag("main_navigation_bar") and hasAnyAncestor(preview),
                useUnmergedTree = true)
            val previewWidth = rule.onNode(preview).fetchSemanticsNode().boundsInRoot.width
            assertEquals(
                expectedFloatingWidth(previewWidth),
                previewBar.fetchSemanticsNode().boundsInRoot.width,
                1f,
            )
            rule.onAllNodes(hasClickAction() and hasAnyAncestor(preview)).assertCountEquals(0)
            rule.onNodeWithText("下部タブバー").performClick()
            rule.onNode(hasText("従来型（全幅）") and hasAnyAncestor(isPopup())).performClick()
            rule.onNodeWithText("従来型（全幅）").assertIsDisplayed()
            assertEquals(previewWidth, previewBar.fetchSemanticsNode().boundsInRoot.width, 1f)
            repeat(2) { rule.onNodeWithContentDescription("戻る").performClick() }

            val fullWidthBar = nav().fetchSemanticsNode().boundsInRoot
            notificationBadge.assertIsDisplayed()
            assertEquals(viewport.left, fullWidthBar.left, 1f)
            assertEquals(viewport.right, fullWidthBar.right, 1f)
            assertTrue("Use the same taps inside both sides of the new bar", y in fullWidthBar.top..fullWidthBar.bottom)
            tapEdges(y)
            assertEquals("The full-width surface should cover the post at both edges", 2, openedPosts.size)

            fun tapTab(name: String) {
                rule.onNodeWithTag("main_tab_$name")
                    .assertIsDisplayed()
                    .performTouchInput { click(center) }
                    .assertIsSelected()
            }
            tapTab("home")
            rule.waitUntil(5_000) { scrollPosition() == 0f }
            list().performScrollToIndex(39)
            val last = rule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().last().boundsInRoot
            assertTrue("The last post must clear the full-width footer", last.bottom <= fullWidthBar.top + 1f)
            val fab = rule.onNodeWithContentDescription("新規投稿").fetchSemanticsNode().boundsInRoot
            assertTrue("The FAB must clear the full-width footer", fab.bottom <= fullWidthBar.top)

            tapTab("notifications")
            tapTab("profile")
            tapTab("explore")
            val input = rule.onNodeWithTag("search_input")
            input.performClick().performTextInput("散歩")
            waitForKeyboard(true)
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("main_navigation_bar").fetchSemanticsNodes().isEmpty()
            }
            input.performImeAction()
            waitForKeyboard(false)
            rule.onNodeWithTag("main_tab_explore").assertIsSelected()
        }
    }

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
            val viewport = content.fetchSemanticsNode().boundsInRoot
            val expanded = header.fetchSemanticsNode().boundsInRoot.height
            expandedHeights[tab.name] = expanded
            savePreview("${tab.name}-expanded")
            repeat(if (tab.name == "profile") 3 else 1) { readOlderPosts(content) }
            val collapsed = header.fetchSemanticsNode().boundsInRoot.height
            assertEquals("${tab.name} viewport must stay fixed while the header collapses",
                viewport, content.fetchSemanticsNode().boundsInRoot)
            assertEquals("${tab.name} toolbar content must retreat completely",
                if (tab.name == "explore") 0f else topInsetPx(), collapsed, 1f)
            rule.onNodeWithTag(tab.retainedTab).assertIsDisplayed()
            assertEquals("${tab.name} tabs must meet the collapsed header without a gap",
                if (tab.name == "explore") rule.onNodeWithTag("search_screen").fetchSemanticsNode().boundsInRoot.top
                else header.fetchSemanticsNode().boundsInRoot.bottom,
                rule.onNodeWithTag(tab.retainedTab).fetchSemanticsNode().boundsInRoot.top, 0.5f)
            assertTrue("${tab.name} content should continue behind the footer",
                content.fetchSemanticsNode().boundsInRoot.bottom > footer.top)
            nav().assertIsDisplayed()
            assertEquals("${tab.name} footer must stay fixed while its header collapses", footer,
                nav().fetchSemanticsNode().boundsInRoot)
            savePreview("${tab.name}-collapsed")
            revealNavigation(content)
            assertEquals("${tab.name} viewport must stay fixed while the header returns",
                viewport, content.fetchSemanticsNode().boundsInRoot)
            assertEquals(footer, nav().fetchSemanticsNode().boundsInRoot)
            // Keep a drag active so fractional offsets are checked before the bars settle.
            rule.mainClock.autoAdvance = false
            try {
                content.performTouchInput {
                    down(Offset(width * .5f, height * .4f))
                    moveBy(Offset(0f, -80.6f))
                }
                rule.mainClock.advanceTimeByFrame()
                assertEquals("${tab.name} tabs must also meet the header during a drag",
                    header.fetchSemanticsNode().boundsInRoot.bottom,
                    rule.onNodeWithTag(tab.retainedTab).fetchSemanticsNode().boundsInRoot.top, 0.1f)
            } finally {
                content.performTouchInput { up() }
                rule.mainClock.autoAdvance = true
            }
            revealNavigation(content)
            heights[tab.name] = header.fetchSemanticsNode().boundsInRoot.height
            positions[tab.name] = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
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

    @Test fun searchHeaderMarginsHideTheScrollingContent() = withHome {
        rule.onNodeWithTag("main_tab_explore").performClick()
        rule.onNodeWithTag("explore_tab_hashtags").performClick()
        val content = rule.onNodeWithTag("explore_list_hashtags")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("explore_list_hashtags").fetchSemanticsNodes().isNotEmpty() }
        // Move real post text and dividers under the expanded search header without hiding it.
        assertSearchHeaderCovers(content)
        savePreview("explore-opaque-header")

        val input = rule.onNodeWithTag("search_input")
        input.performClick().performTextInput("散歩")
        input.performImeAction()
        waitForKeyboard(false)
        rule.onNodeWithTag("search_target_hashtags").performClick()
        assertSearchHeaderCovers(rule.onNodeWithTag("search_results_hashtags"))
        savePreview("search-opaque-header")
    }

    private fun assertSearchHeaderCovers(content: SemanticsNodeInteraction) {
        content.performScrollToIndex(8)
        readOlderPosts(content)
        content.performTouchInput {
            swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .45f), 600)
        }
        rule.waitForIdle()
        assertSearchHeaderIsOpaque()
    }

    private fun assertSearchHeaderIsOpaque() {
        val header = rule.onNodeWithTag("main_search_header").fetchSemanticsNode().boundsInRoot
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // The area outside the rounded field must be the same opaque surface as the tabs.
        val expected = pixels[1, header.bottom.toInt() + 2]
        for (x in 1 until pixels.width - 1) {
            val actual = pixels[x, header.top.toInt() + 2]
            assertTrue("Search header top margin must hide the list at x=$x, header=$header: $actual vs $expected",
                abs(actual.red - expected.red) + abs(actual.green - expected.green) +
                    abs(actual.blue - expected.blue) < .015f)
        }
        for (y in header.top.toInt() + 1 until header.bottom.toInt() - 1) {
            for (x in listOf(1, pixels.width - 2)) {
                val actual = pixels[x, y]
                val difference = abs(actual.red - expected.red) + abs(actual.green - expected.green) +
                    abs(actual.blue - expected.blue)
                assertTrue("Search header margin must cover the list at ($x, $y): $actual vs $expected",
                    difference < .015f)
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
        // Native IME visibility can precede Compose receiving its animated insets.
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("main_navigation_bar").fetchSemanticsNodes().isEmpty()
        }
        nav().assertDoesNotExist()
        val header = rule.onNodeWithTag("main_search_header")
        val expanded = header.fetchSemanticsNode().boundsInRoot.height
        results.performTouchInput {
            swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .05f), 600)
        }
        assertEquals("Input must remain visible while editing", expanded, header.fetchSemanticsNode().boundsInRoot.height, 1f)
        input.performImeAction()
        waitForKeyboard(false)
        nav().assertIsDisplayed()
        rule.onNodeWithTag("main_tab_explore").assertIsSelected()
        readOlderPosts(results)
        assertEquals(0f, header.fetchSemanticsNode().boundsInRoot.height, 1f)
        nav().assertIsDisplayed()
        rule.onNodeWithTag("search_target_posts").assertIsDisplayed()

        pressBack()
        rule.onNodeWithTag("search_open").assertExists()
        rule.onNodeWithTag("main_tab_explore").assertIsSelected()
        pressBack()
        rule.onNodeWithTag("main_tab_home").assertIsSelected()
    }

    @Test fun profileLastPostCanMoveAboveTheFixedFooter() = withHome {
        rule.onNodeWithTag("main_tab_profile").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("profile_scroll").fetchSemanticsNodes().isNotEmpty() }
        val restingFooter = nav().fetchSemanticsNode().boundsInRoot
        val content = rule.onNodeWithTag("profile_scroll")
        readOlderPosts(content)
        content.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
        rule.waitForIdle()
        nav().assertIsDisplayed()
        assertEquals(restingFooter, nav().fetchSemanticsNode().boundsInRoot)
        val last = rule.onNodeWithText("本文 39", substring = true)
        last.assertIsDisplayed()
        val lastPost = rule.onAllNodesWithTag("timeline_status").fetchSemanticsNodes().last().boundsInRoot
        assertTrue("Last profile post must clear the fixed footer",
            lastPost.bottom <= restingFooter.top + 1f)
        savePreview("profile-end")
    }

    private fun topInsetPx(): Float {
        var inset = 0
        rule.runOnUiThread {
            inset = requireNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
        }
        return inset.toFloat()
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
    private fun revealNavigation(target: SemanticsNodeInteraction = list()) {
        target.performTouchInput {
            swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .65f), 600)
        }
        rule.waitForIdle()
        nav().assertIsDisplayed()
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

    private fun withHome(
        testPreferences: UserPreferencesStore? = null,
        onStatusClick: (String) -> Unit = {},
        restoration: StateRestorationTester? = null,
        openDetails: Boolean = false,
        notificationRequest: MutableState<NotificationOpenRequest?>? = null,
        check: (Models) -> Unit,
    ) {
        val session = AccountSession("home-edge", "https://example.test", "me", "me", "自分", "", "")
        val posts = List(40) { index -> TimelineStatus(
            timelineId = "post-$index", statusId = "post-$index", createdAt = "2026-11-04T00:00:00Z",
            author = StatusAuthor("author-$index", "投稿者 $index", "author$index@example.test", ""),
            boostedBy = null, contentHtml = "<p>本文 $index</p><p>散歩の途中で見つけた景色について、ゆっくり読める投稿です。</p>",
            spoilerText = "", sensitive = false, visibility = "public", url = null,
            repliesCount = 0, boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
        ) }
        val tags = List(40) { SearchTag("散歩$it", "https://example.test/tags/walk$it", postingAccounts = 3L) }
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(posts, null, true))
            override suspend fun getExplore(session: AccountSession, feed: ExploreFeed, cursor: String?, limit: Int) =
                Result.success(if (feed == ExploreFeed.Hashtags) ExplorePage(tags = tags) else ExplorePage(statuses = posts))
            override suspend fun getProfile(session: AccountSession, accountId: String) = Result.success(UserProfile(
                author = StatusAuthor("me", "自分", "me@example.test", ""), headerUrl = "", noteHtml = "<p>プロフィール紹介</p>",
                followersCount = 10, followingCount = 20, statusesCount = 40, statuses = posts,
                url = "https://example.test/@me", endReached = true, isOwnProfile = true,
            ))
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String, tab: ProfileStatusTab, maxId: String?) =
                Result.success(TimelinePage(posts, null, true))
            override suspend fun getFeaturedTags(session: AccountSession, accountId: String) = Result.success(emptyList<FeaturedTag>())
            override suspend fun search(session: AccountSession, query: String, target: SearchTarget, offset: Int, limit: Int) =
                Result.success(SearchPage(SearchResults(emptyList(), posts, tags), null, true))
            override suspend fun getNotifications(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(NotificationPage(List(40) { i ->
                    val status = posts[i].takeIf { openDetails && i == 0 }
                    TimelineNotification("notice-$i", if (status == null) "follow" else "favourite",
                        "2026-11-04T00:00:00Z", posts[i].author, status)
                }, null, true))
            override suspend fun getNotificationMarker(session: AccountSession): Result<String?> =
                Result.success(if (testPreferences != null) "notice-2" else null)
        }
        val auth = object : AuthRepository {
            private var active = session
            private val accounts = if (notificationRequest == null) listOf(session)
                else listOf(session, session.copy(sessionId = "other-edge", accountId = "other"))

            override suspend fun createAuthorizationUrl(instanceUrl: String) = Result.success("")
            override suspend fun completeAuthorization(callbackUrl: String) = Result.success(session)
            override suspend fun restoreSession() = active
            override suspend fun getSessions() = accounts
            override suspend fun switchSession(sessionId: String) =
                accounts.find { it.sessionId == sessionId }?.also { active = it }

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
            main = MainSessionViewModel(auth, repository, preferencesStore = testPreferences).also { store.put("main", it) }
            timeline = TimelineViewModel(repository, main.browsing, SavedStateHandle()).also { store.put("timeline", it) }
            search = SearchViewModel(repository, main.browsing).also { store.put("search", it) }
            notifications = NotificationsViewModel(repository, main.browsing).also { store.put("notifications", it) }
            profile = ProfileViewModel(repository, main.browsing).also { store.put("profile", it) }
            actions = StatusActionsViewModel(repository, main.browsing).also { store.put("actions", it) }
        }
        try {
            rule.waitUntil(5_000) { timeline.uiState.value.statuses.isNotEmpty() }
            val maintenance = testPreferences?.let {
                SettingsMaintenanceViewModel(object : AppMaintenanceRepository {
                    override val versionName = "test"
                    override val versionCode = 1L
                    override suspend fun clearImageCache() = Unit
                }).also { store.put("settings", it) }
            }
            var showSettings by mutableStateOf(false)
            val content: @Composable () -> Unit = {
                MastodonClientTheme(themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light) {
                    val navigation = rememberNavController()
                    val currentEntry by navigation.currentBackStackEntryAsState()
                    ScreenNavHost(navigation, startDestination = Route.Timeline) {
                        animatedScreen<Route.Timeline> { entry ->
                            HomeTimelineScreen(timeline, main, search, notifications, profile, actions,
                                fabPositionStore = object : FabPositionStore {
                                    override val fabPosition = flowOf<FabPosition?>(null)
                                    override suspend fun setFabPosition(position: FabPosition) = Unit
                                    override suspend fun resetFabPosition() = Unit
                                },
                                notificationOpenRequest = notificationRequest?.value,
                                onNotificationOpenHandled = { notificationRequest?.value = null },
                                onLoggedOut = {}, onStatusClick = { statusId ->
                                    if (openDetails) navigation.navigate(Route.StatusDetail(statusId))
                                    else onStatusClick(statusId)
                                }, onCompose = {}, onQuote = { _, _ -> },
                                onOpenLink = {}, openLinksInApp = true, onOpenLinksInAppChange = {},
                                onAccountClick = {}, onMediaClick = { _, _ -> },
                                onSettings = { showSettings = testPreferences != null }, onFollowers = {},
                                onFollowing = {}, onEditStatus = {}, onOpenLists = {}, onOpenBookmarks = {},
                                onOpenFavourites = {}, tabBackEnabled = currentEntry?.id == entry.id)
                        }
                        animatedScreen<Route.StatusDetail> {
                            Text("Detail ${it.toRoute<Route.StatusDetail>().statusId}")
                        }
                    }
                    if (showSettings && testPreferences != null && maintenance != null) {
                        val preferences by testPreferences.preferences.collectAsStateWithLifecycle(initialValue = AppPreferences())
                        SettingsScreen(
                            store = testPreferences, preferences = preferences,
                            activeSession = session, sessions = listOf(session), maintenance = maintenance,
                            onBack = { showSettings = false }, onLogout = {}, onAddAccount = {},
                        )
                    }
                }
            }
            if (restoration != null) restoration.setContent(content) else rule.setContent(content)
            rule.onNodeWithTag("timeline_list").assertIsDisplayed()
            check(Models(main, timeline))
        } finally {
            rule.runOnIdle { store.clear() }
        }
    }
}
