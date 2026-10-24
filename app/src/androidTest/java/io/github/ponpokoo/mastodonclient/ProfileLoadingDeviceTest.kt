package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.FeaturedTag
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileTabUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileLoadingDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val state = mutableStateOf(ProfileUiState())
    private val viewportHeight = mutableStateOf(700.dp)
    private val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
    private val profile = UserProfile(author, "", "", 3, 4, 5, emptyList(), endReached = true)
    private val post = TimelineStatus("post", "post", "2026-10-24T00:00:00Z", author, null,
        "<p>取得済みの投稿</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())

    @Test fun initialPostsLoadingFollowsTabsRatherThanTheViewportCenter() {
        showProfile()
        rule.onNodeWithTag("list_initial_loading").assertIsDisplayed()
        rule.onNodeWithText("プロフィールを表示できませんでした").assertDoesNotExist()
        rule.runOnIdle {
            state.value = ProfileUiState(profile = profile,
                profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(isLoadingMore = true)))
        }
        rule.onNodeWithText("テストユーザー").assertIsDisplayed()
        rule.onNode(hasText("投稿を読み込み中…") and
            hasAnyAncestor(hasTestTag("profile_posts_posts"))).assertIsDisplayed()
        rule.onNodeWithTag("list_initial_loading").assertDoesNotExist()
        rule.onNodeWithTag("list_loading_more").assertDoesNotExist()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "profile-loading-inline.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val initialGap = loadingGapBelowTabs()
        rule.runOnIdle { viewportHeight.value = 600.dp }
        assertEquals(initialGap, loadingGapBelowTabs(), 0.5f)
        rule.runOnIdle {
            state.value = state.value.copy(featuredTags = listOf(FeaturedTag("tag", "写真", 12)))
        }
        rule.onNodeWithText("#写真 (12)").assertIsDisplayed()
        assertEquals(initialGap, loadingGapBelowTabs(), 0.5f)
    }

    @Test fun unloadedTabShowsItsOwnLoadingAndFailureReplacesItWithRetry() {
        state.value = ProfileUiState(profile = profile, profileTabs = mapOf(
            ProfileStatusTab.Posts to ProfileTabUiState(statuses = listOf(post), isLoaded = true, endReached = true)))
        showProfile()
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.runOnIdle {
            state.value = state.value.copy(profileSelectedTab = ProfileStatusTab.Replies,
                profileTabs = state.value.profileTabs + (ProfileStatusTab.Replies to ProfileTabUiState(isLoading = true)))
        }
        rule.onNode(hasText("投稿を読み込み中…") and
            hasAnyAncestor(hasTestTag("profile_posts_replies"))).assertIsDisplayed()
        rule.onNodeWithText("取得済みの投稿").assertIsNotDisplayed()
        rule.runOnIdle {
            state.value = state.value.copy(profileTabs = state.value.profileTabs +
                (ProfileStatusTab.Replies to ProfileTabUiState(error = "投稿を取得できませんでした")))
        }
        rule.onNode(hasTestTag("profile_posts_loading") and
            hasAnyAncestor(hasTestTag("profile_posts_replies"))).assertDoesNotExist()
        rule.onNodeWithText("投稿を取得できませんでした").assertIsDisplayed()
        rule.onNodeWithText("再試行").assertIsDisplayed()
    }

    @Test fun refreshAndPaginationKeepLoadedPostsWithoutInitialLoading() {
        state.value = ProfileUiState(profile = profile, profileTabs = mapOf(ProfileStatusTab.Posts to
            ProfileTabUiState(statuses = listOf(post), isLoaded = true, isRefreshing = true, isPullRefreshing = true)))
        showProfile()
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.onNodeWithTag("profile_posts_loading").assertDoesNotExist()
        rule.onNodeWithTag("list_loading_more").assertDoesNotExist()
        rule.runOnIdle {
            state.value = state.value.copy(profileTabs = mapOf(ProfileStatusTab.Posts to
                ProfileTabUiState(statuses = listOf(post), isLoaded = true, isLoadingMore = true)))
        }
        rule.onNodeWithText("取得済みの投稿").assertIsDisplayed()
        rule.onNodeWithTag("list_loading_more").assertIsDisplayed()
        rule.onNodeWithTag("profile_posts_loading").assertDoesNotExist()
    }

    private fun loadingGapBelowTabs(): Float {
        val loading = rule.onNodeWithTag("profile_posts_loading").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tab = rule.onNodeWithTag("profile_status_tab_posts").fetchSemanticsNode().boundsInRoot
        return loading.top - tab.bottom
    }

    private fun showProfile() {
        rule.setContent {
            MastodonClientTheme {
                Surface(Modifier.width(360.dp).height(viewportHeight.value)) {
                    ProfileContent(state = state.value, selectedTab = state.value.profileSelectedTab,
                        padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                        onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                        onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> })
                }
            }
        }
    }
}
