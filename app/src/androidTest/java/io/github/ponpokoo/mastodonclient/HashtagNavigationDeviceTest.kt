package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.ScreenViewModelFactory
import io.github.ponpokoo.mastodonclient.feature.common.StatusActionsViewModel
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineScreen
import io.github.ponpokoo.mastodonclient.feature.tag.HashtagTimelineViewModel
import io.github.ponpokoo.mastodonclient.navigation.Route
import io.github.ponpokoo.mastodonclient.navigation.openHashtag
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real navigation and screens with local fixtures; no requests reach a Mastodon account. */
class HashtagNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val account = AccountSession("tag-navigation", "https://example.test", "me", "me", "Me", "", "fixture")
    private val browsing = BrowsingSession().apply { activate(account) }
    private lateinit var navigation: NavHostController
    private lateinit var currentModel: HashtagTimelineViewModel
    private val timelineRequests = mutableListOf<String>()
    private val tagRequests = mutableListOf<String>()
    private val followedTags = mutableListOf<String>()
    private val repository = object : TimelineRepository {
        override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
            Result.success(TimelinePage(emptyList(), null, true))

        override suspend fun getHashtagTimeline(session: AccountSession, hashtag: String, maxId: String?, limit: Int): Result<TimelinePage> {
            timelineRequests += hashtag
            return Result.success(TimelinePage(List(20) { index ->
                TimelineStatus("$hashtag-$index", "$hashtag-$index", "2026-10-20T00:00:00Z",
                    StatusAuthor("author", "Author", "author", ""), null,
                    "<p>$hashtag post $index</p>", "", false, "public", null, 0, 0, 0,
                    mediaAttachments = emptyList())
            }, null, true))
        }

        override suspend fun getTag(session: AccountSession, name: String): Result<SearchTag> {
            tagRequests += name
            return Result.success(SearchTag(name, "https://example.test/tags/$name", following = false))
        }

        override suspend fun setTagFollowing(session: AccountSession, name: String, following: Boolean): Result<SearchTag> {
            followedTags += name
            return Result.success(SearchTag(name, "https://example.test/tags/$name", following = following))
        }
    }

    private fun render() {
        rule.setContent {
            MaterialTheme {
                navigation = rememberNavController()
                NavHost(navigation, startDestination = Route.Timeline) {
                    composable<Route.Timeline> {
                        TextButton(onClick = { navigation.openHashtag("Photo", fromTrend = true) }) {
                            Text("タグを開く")
                        }
                    }
                    composable<Route.HashtagTimeline> { entry ->
                        val route = entry.toRoute<Route.HashtagTimeline>()
                        val model: HashtagTimelineViewModel = viewModel(
                            factory = HashtagTimelineViewModel.Factory(route.hashtag, repository, browsing),
                        )
                        val actions: StatusActionsViewModel = viewModel(factory = ScreenViewModelFactory {
                            StatusActionsViewModel(repository, browsing)
                        })
                        SideEffect { currentModel = model }
                        HashtagTimelineScreen(
                            hashtag = route.hashtag, viewModel = model, actionsViewModel = actions,
                            currentAccountId = account.accountId, preferences = AppPreferences(),
                            onBack = { navigation.popBackStack() }, onStatusClick = {}, onReply = {},
                            onQuote = { _, _ -> }, onOpenLink = {}, onAccountClick = {},
                            onMediaClick = { _, _ -> }, onEditStatus = {}, fromTrend = route.fromTrend,
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("タグを開く").performClick()
        waitForTag("Photo")
    }

    private fun waitForTag(name: String) {
        rule.waitUntil(5_000) {
            ::currentModel.isInitialized && !currentModel.uiState.value.isLoading && currentModel.uiState.value.tag?.name == name
        }
        rule.waitForIdle()
        rule.onNodeWithText("#$name").assertIsDisplayed()
    }

    private fun scrollPosition() = rule.onNode(hasScrollAction()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun differentTagUsesItsOwnPostsAndActionsAndBackRestoresOriginalPosition() {
        render()
        rule.onNode(hasScrollAction()).performScrollToIndex(12)
        rule.onNodeWithText("Photo post 12").assertIsDisplayed()
        val originalPosition = scrollPosition()
        val originalModel = currentModel
        rule.runOnIdle { navigation.openHashtag("Other") }
        waitForTag("Other")
        rule.onNodeWithText("Other post 0").assertIsDisplayed()
        rule.runOnIdle { assertNotSame(originalModel, currentModel) }

        rule.onNodeWithTag("tag_menu").performClick()
        rule.onNodeWithText("フォローする").performClick()
        rule.waitUntil(5_000) { currentModel.uiState.value.tag?.following == true }
        rule.runOnIdle {
            assertEquals(listOf("Other"), followedTags)
            currentModel.refresh()
        }
        rule.waitUntil(5_000) { !currentModel.uiState.value.isLoading }
        rule.onNodeWithContentDescription("戻る").performClick()
        waitForTag("Photo")

        rule.onNodeWithText("Photo post 12").assertIsDisplayed()
        rule.onNodeWithTag("tag_subscription").assertIsDisplayed()
        assertEquals(originalPosition, scrollPosition(), 0f)
        rule.runOnIdle {
            assertSame(originalModel, currentModel)
            assertEquals(listOf("Photo", "Other", "Other"), timelineRequests)
            assertEquals(listOf("Photo", "Other"), tagRequests)
        }
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText("タグを開く").assertIsDisplayed()
    }

    @Test fun sameTagIncludingDifferentCaseKeepsEntryPostsPositionAndTrendControls() {
        render()
        rule.onNode(hasScrollAction()).performScrollToIndex(12)
        val originalPosition = scrollPosition()
        val originalModel = currentModel
        val originalEntry = navigation.currentBackStackEntry
        rule.runOnIdle {
            navigation.openHashtag("Photo")
            navigation.openHashtag("photo")
        }
        rule.waitForIdle()

        rule.onNodeWithText("#Photo").assertIsDisplayed()
        rule.onNodeWithText("Photo post 12").assertIsDisplayed()
        rule.onNodeWithTag("tag_subscription").assertIsDisplayed()
        rule.onNodeWithTag("tag_menu").assertDoesNotExist()
        assertEquals(originalPosition, scrollPosition(), 0f)
        rule.runOnIdle {
            assertSame(originalEntry, navigation.currentBackStackEntry)
            assertSame(originalModel, currentModel)
            assertEquals(listOf("Photo"), timelineRequests)
            assertEquals(listOf("Photo"), tagRequests)
        }
        rule.onNodeWithContentDescription("戻る").performClick()
        rule.onNodeWithText("タグを開く").assertIsDisplayed()
    }
}
