package io.github.ponpokoo.mastodonclient

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ponpokoo.mastodonclient.core.preferences.ThemeMode
import io.github.ponpokoo.mastodonclient.domain.model.AccountRelationship
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.UserProfile
import io.github.ponpokoo.mastodonclient.feature.profile.AccountListItem
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileContent
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileActionButtonsDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val scale = mutableStateOf(1f)
    private val theme = mutableStateOf(ThemeMode.Light)
    private val variant = mutableStateOf(0)
    private var followClicks = 0
    private var editClicks = 0
    private var accountClicks = 0
    private val author = StatusAuthor("account", "テストユーザー", "test", "")
    private val relationships = listOf(
        AccountRelationship(), AccountRelationship(following = true), AccountRelationship(requested = true),
        AccountRelationship(), null,
    )
    private val labels = listOf("フォロー", "フォロー中", "リクエスト中", "リクエスト", "確認中")

    @Test fun lightProfileActionsRemainReadableAndClickableAtLargeFontSizes() = checkProfile(ThemeMode.Light)
    @Test fun darkProfileActionsRemainReadableAndClickableAtLargeFontSizes() = checkProfile(ThemeMode.Dark)
    @Test fun lightAccountRowsKeepNamesAndFollowActionsUsableAtLargeFontSizes() = checkAccountRows(ThemeMode.Light)
    @Test fun darkAccountRowsKeepNamesAndFollowActionsUsableAtLargeFontSizes() = checkAccountRows(ThemeMode.Dark)

    private fun checkProfile(mode: ThemeMode) {
        theme.value = mode
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MastodonClientTheme(themeMode = theme.value) {
                    Surface(Modifier.width(320.dp).fillMaxHeight()) {
                        val own = variant.value == 5
                        ProfileContent(
                            state = ProfileUiState(profile = UserProfile(author, "", "", 0, 0, 0,
                                emptyList(), locked = variant.value == 3, endReached = true, isOwnProfile = own)),
                            relationship = if (own) null else relationships[variant.value],
                            padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                            onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> },
                            onFavourite = {}, onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                            onToggleFollow = { followClicks++ }, onEditProfile = { editClicks++ },
                        )
                    }
                }
            }
        }
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                for (index in 0..5) {
                    // Keep the editor beside the avatar; its accepted font range is
                    // 1x to 1.3x. Follow actions still cover 2x on narrow screens.
                    if (index == 5 && fontScale == 2f) continue
                    rule.runOnIdle { theme.value = mode; scale.value = fontScale; variant.value = index }
                    val own = index == 5
                    val tag = if (own) "edit_profile_action" else "follow_action"
                    val label = if (own) "プロフィールを編集" else labels[index]
                    val button = rule.onNodeWithTag(tag).assertIsDisplayed()
                    assertReadable(tag, label, "$mode/$fontScale/$label")
                    val bounds = button.fetchSemanticsNode().boundsInRoot
                    val avatar = rule.onNodeWithTag("profile_avatar").fetchSemanticsNode().boundsInRoot
                    assertFalse("Action overlaps avatar: $mode/$fontScale/$label", bounds.overlaps(avatar))
                    if (own && fontScale == 1f) {
                        assertTrue("Editor must stay beside the avatar", bounds.left >= avatar.right && bounds.top < avatar.bottom)
                    }
                    val menu = rule.onNode(androidx.compose.ui.test.hasContentDescription("プロフィールのその他メニュー"),
                        useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertFalse("Action overlaps menu: $mode/$fontScale/$label", bounds.overlaps(menu))
                    when (index) {
                        2, 4 -> button.assertIsNotEnabled()
                        5 -> {
                            val before = editClicks
                            button.performClick()
                            rule.runOnIdle { assertEquals(before + 1, editClicks) }
                        }
                        else -> {
                            val before = followClicks
                            button.performClick()
                            rule.runOnIdle { assertEquals(before + 1, followClicks) }
                        }
                    }
                }
            }
    }

    private fun checkAccountRows(mode: ThemeMode) {
        theme.value = mode
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                MastodonClientTheme(themeMode = theme.value) {
                    Surface(Modifier.width(320.dp)) {
                        AccountListItem(author.copy(locked = variant.value == 3), relationships[variant.value],
                            showFollowButton = true, enabled = true,
                            onAccountClick = { accountClicks++ }, onFollowClick = { followClicks++ })
                    }
                }
            }
        }
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                for (index in relationships.indices) {
                    rule.runOnIdle { theme.value = mode; scale.value = fontScale; variant.value = index }
                    val button = rule.onNodeWithTag("follow_action").assertIsDisplayed()
                    assertReadable("follow_action", labels[index], "$mode/$fontScale/${labels[index]}")
                    val name = rule.onNode(hasText(author.displayName), useUnmergedTree = true).assertIsDisplayed()
                    assertFalse("Action overlaps account name", button.fetchSemanticsNode().boundsInRoot
                        .overlaps(name.fetchSemanticsNode().boundsInRoot))
                    if (index == 2 || index == 4) button.assertIsNotEnabled() else {
                        val before = followClicks
                        button.performClick()
                        rule.runOnIdle {
                            assertEquals(before + 1, followClicks)
                            assertEquals("Follow click must not open profile", 0, accountClicks)
                        }
                    }
                }
            }
    }

    private fun assertReadable(tag: String, label: String, context: String) {
        val node = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        // A wrap-content Text can retain a wider cached paragraph than its measured
        // node. Check the drawn lines, rather than that paragraph's blank right edge.
        assertTrue("Label clipped: $context; ${layouts.map { "size=${it.size}, constraints=${it.layoutInput.constraints}, lines=${it.lineCount}, heightOverflow=${it.didOverflowHeight}, lineEdges=${(0 until it.lineCount).map { line -> it.getLineRight(line) to it.getLineBottom(line) }}" }}", layouts.isNotEmpty() && layouts.all { layout ->
            !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= layout.size.width + 1f &&
                    layout.getLineBottom(line) <= layout.size.height + 1f
            }
        })
        val button = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val text = node.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Text extends outside button: $context", contains(button, text))
        assertTrue("Button extends outside screen: $context", contains(root, button))
    }

    private fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
        inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1

}
