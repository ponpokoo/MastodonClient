package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import java.io.File
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import io.github.ponpokoo.mastodonclient.domain.model.ProfileStatusTab
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineStatus
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileTabUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileTabSwipeDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val selections = mutableListOf<ProfileStatusTab>()
    private lateinit var listStates: List<LazyListState>
    private lateinit var headerListState: LazyListState
    private val fontScale = mutableStateOf(1f)
    private val themeMode = mutableStateOf(ThemeMode.Light)

    @Test fun enlargedLabelsRemainReadableAndTabsWorkAfterThemeAndFontChanges() {
        showProfile()
        for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                rule.runOnIdle { themeMode.value = theme; fontScale.value = scale }
                tab(ProfileStatusTab.Replies).performClick()
                assertSelected(ProfileStatusTab.Replies)
                for ((index, label) in listOf("投稿", "投稿と返信", "メディア").withIndex()) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    val tag = "profile_status_tab_${ProfileStatusTab.entries[index].name.lowercase()}"
                    val node = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Label clipped at $theme / $scale: $label", layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
                }
                val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
                File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "profile-tabs-${theme.name.lowercase()}-$scale.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                nextTab()
                assertSelected(ProfileStatusTab.Media)
                previousTab()
                assertSelected(ProfileStatusTab.Replies)
                previousTab()
                assertSelected(ProfileStatusTab.Posts)
            }
        }
    }

    @Test fun draggingUpdatesTabButSelectsDestinationOnlyAfterRelease() {
        showProfile()
        rule.onNodeWithTag("profile_status_pager").performTouchInput {
            down(Offset(width * 0.9f, center.y))
            moveTo(Offset(width * 0.1f, center.y), delayMillis = 400)
        }
        rule.waitForIdle()
        tab(ProfileStatusTab.Replies).assertIsSelected()
        rule.runOnIdle { assertTrue(selections.isEmpty()) }
        rule.onNodeWithTag("profile_status_pager").performTouchInput { up() }
        assertSelected(ProfileStatusTab.Replies)
        rule.runOnIdle { assertEquals(listOf(ProfileStatusTab.Replies), selections) }
    }

    @Test fun swipesSelectAdjacentTabsAndStopAtBothEnds() {
        showProfile()
        val headerBounds = rule.onNodeWithTag("profile_header").fetchSemanticsNode().boundsInRoot
        assertSelected(ProfileStatusTab.Posts)
        nextTab()
        assertSelected(ProfileStatusTab.Replies)
        assertEquals(headerBounds, rule.onNodeWithTag("profile_header").fetchSemanticsNode().boundsInRoot)
        nextTab()
        assertSelected(ProfileStatusTab.Media)
        assertEquals(headerBounds, rule.onNodeWithTag("profile_header").fetchSemanticsNode().boundsInRoot)
        nextTab()
        assertSelected(ProfileStatusTab.Media)
        previousTab()
        assertSelected(ProfileStatusTab.Replies)
        previousTab()
        assertSelected(ProfileStatusTab.Posts)
        previousTab()
        assertSelected(ProfileStatusTab.Posts)
        rule.runOnIdle {
            assertEquals(listOf(ProfileStatusTab.Replies, ProfileStatusTab.Media,
                ProfileStatusTab.Replies, ProfileStatusTab.Posts), selections)
        }
    }

    @Test fun tappingDistantTabMovesPagerWithoutSelectingIntermediateTab() {
        showProfile()
        tab(ProfileStatusTab.Media).performClick()
        assertSelected(ProfileStatusTab.Media)
        previousTab()
        assertSelected(ProfileStatusTab.Replies)
        rule.runOnIdle {
            assertEquals(listOf(ProfileStatusTab.Media, ProfileStatusTab.Replies), selections)
        }
    }

    @Test fun swipingBackRestoresThePostsScrollPosition() {
        showProfile()
        repeat(3) { rule.onNodeWithTag("profile_status_pager").performTouchInput { swipeUp() } }
        var index = 0
        var offset = 0
        rule.runOnIdle {
            index = listStates[0].firstVisibleItemIndex
            offset = listStates[0].firstVisibleItemScrollOffset
            assertTrue(index > 0 || offset > 0)
            assertTrue(headerListState.firstVisibleItemIndex > 0)
        }
        assertSelected(ProfileStatusTab.Posts)
        nextTab()
        previousTab()
        rule.runOnIdle {
            assertEquals(index, listStates[0].firstVisibleItemIndex)
            assertEquals(offset, listStates[0].firstVisibleItemScrollOffset)
        }
    }

    private fun showProfile() {
        val selected = mutableStateOf(ProfileStatusTab.Posts)
        val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
        val statuses = ProfileStatusTab.entries.associateWith { tab ->
            List(25) { index -> TimelineStatus(
                timelineId = "${tab.name}-$index", statusId = "${tab.name}-$index",
                createdAt = "2026-10-04T00:00:00Z", author = author, boostedBy = null,
                contentHtml = "<p>${tab.name}の投稿 $index</p>", spoilerText = "", sensitive = false,
                visibility = "public", url = null, repliesCount = 0, boostsCount = 0,
                favouritesCount = 0, mediaAttachments = emptyList(),
            ) }
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MastodonClientTheme(themeMode = themeMode.value) {
                    Surface {
                        listStates = List(ProfileStatusTab.entries.size) { rememberLazyListState() }
                        headerListState = rememberLazyListState()
                        ProfileContent(
                            state = ProfileUiState(profile = UserProfile(author, "", "", 0, 0, 25,
                                statuses.getValue(selected.value), endReached = true),
                                profileTabs = statuses.mapValues { (_, posts) -> ProfileTabUiState(
                                    statuses = posts, isLoaded = true, endReached = true) }),
                            padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                            onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                            onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                            selectedTab = selected.value, listStates = listStates, headerListState = headerListState,
                            onSelectTab = { selections += it; selected.value = it },
                        )
                    }
                }
            }
        }
    }

    private fun tab(tab: ProfileStatusTab) = rule.onNodeWithTag("profile_status_tab_${tab.name.lowercase()}")

    private fun assertSelected(tab: ProfileStatusTab) { tab(tab).assertIsSelected() }
    private fun nextTab() { rule.onNodeWithTag("profile_status_pager").performTouchInput { swipeLeft() }; rule.waitForIdle() }
    private fun previousTab() { rule.onNodeWithTag("profile_status_pager").performTouchInput { swipeRight() }; rule.waitForIdle() }
}
