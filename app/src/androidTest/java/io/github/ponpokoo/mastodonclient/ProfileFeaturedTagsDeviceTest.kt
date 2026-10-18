package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileTabUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileFeaturedTagsDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val author = StatusAuthor("author", "テストユーザー", "test@example.test", "")
    private fun status(id: String) = TimelineStatus(id, id, "2026-10-18T00:00:00Z", author, null,
        "<p>$id</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList())
    private val profile = UserProfile(author, "", "<p>プロフィールの自己紹介</p>", 3, 4, 5,
        listOf(status("通常の投稿")), fields = listOf(ProfileField("Webサイト", "<p>プロフィール項目</p>", null)),
        pinnedStatuses = listOf(status("固定の投稿")), endReached = true)

    @Test fun chipsFilterInPlaceAndRemovePinnedPriorityInLightTheme() = verify(ThemeMode.Light)
    @Test fun chipsFilterInPlaceAndRemovePinnedPriorityInDarkTheme() = verify(ThemeMode.Dark)
    @Test fun longTagsRemainUsableWithLargeText() = verify(ThemeMode.Light, 1.6f)

    private fun verify(theme: ThemeMode, fontScale: Float = 1f) {
        val state = mutableStateOf(ProfileUiState(profile = profile,
            featuredTags = listOf(FeaturedTag("one", "写真"), FeaturedTag("two", "NowPlaying"),
                FeaturedTag("three", "長いハッシュタグも画面幅に合わせて折り返して表示します")),
            profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(statuses = profile.statuses, isLoaded = true, endReached = true))))
        rule.setContent {
            MastodonClientTheme(themeMode = theme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(Modifier.fillMaxSize()) {
                ProfileContent(state = state.value, padding = PaddingValues(), onRetry = {}, onRefresh = {},
                    onStatusClick = {}, onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                    onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    onSelectHashtag = { name ->
                        val selected = name.takeUnless { it == state.value.selectedHashtag }
                        val statuses = if (selected == null) profile.statuses else listOf(status("タグ付きの投稿"))
                        state.value = state.value.copy(selectedHashtag = selected, profile = profile.copy(statuses = statuses),
                            profileTabs = mapOf(ProfileStatusTab.Posts to ProfileTabUiState(statuses = statuses, isLoaded = true, endReached = true)))
                    })
                }
                }
            }
        }
        val note = rule.onNodeWithText("プロフィールの自己紹介").fetchSemanticsNode().boundsInRoot
        val chip = rule.onNodeWithText("#写真").fetchSemanticsNode().boundsInRoot
        val field = rule.onNodeWithText("プロフィール項目").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.top > note.bottom && field.top > chip.bottom)
        rule.onNodeWithText("#写真").performClick()
        rule.runOnIdle { assertEquals("写真", state.value.selectedHashtag) }
        rule.onNodeWithText("固定された投稿").assertDoesNotExist()
        rule.onNodeWithText("通常の投稿").assertDoesNotExist()
        rule.onNodeWithText("タグ付きの投稿").assertExists()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = output?.let { File(it) } ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        File(directory, "profile-featured-tags-${theme.name.lowercase()}-$fontScale.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("#NowPlaying").performClick()
        rule.runOnIdle { assertEquals("NowPlaying", state.value.selectedHashtag) }
        rule.onNodeWithText("#NowPlaying").performClick()
        rule.runOnIdle { assertEquals(null, state.value.selectedHashtag) }
        rule.onNodeWithText("固定された投稿").assertExists()
    }

    @Test fun returningToProfilePreservesTheExistingUnfilteredScrollPosition() {
        val posts = List(20) { status("post-$it") }
        val restoredList = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 12)
        rule.setContent {
            MastodonClientTheme {
                ProfileContent(state = ProfileUiState(profile = profile.copy(statuses = posts, pinnedStatuses = emptyList())),
                    padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                    onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                    onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    listStates = listOf(restoredList, LazyListState(), LazyListState()))
            }
        }
        rule.runOnIdle {
            assertEquals(5, restoredList.firstVisibleItemIndex)
            assertEquals(12, restoredList.firstVisibleItemScrollOffset)
        }
    }
}
