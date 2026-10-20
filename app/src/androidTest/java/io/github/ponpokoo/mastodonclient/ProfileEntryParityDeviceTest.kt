package io.github.ponpokoo.mastodonclient

import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.TimelineRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.profile.AccountProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileScreen
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileViewModel
import io.github.ponpokoo.mastodonclient.ui.theme.MastodonClientTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileEntryParityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val session = AccountSession("fixture", "https://fixture.example", "me", "me", "Me", "", "unused")
    private val author = StatusAuthor("me", "Me", "me@fixture.example", "")
    private val poll = StatusPoll("poll", null, false, false, 0, 0, false, emptySet(),
        listOf(PollOption("First option", 0), PollOption("Second option", 0)))
    private val post = TimelineStatus("post", "post", "2026-10-20T00:00:00Z", author, null,
        "<p>Profile fixture</p>", "", false, "public", null, 0, 0, 0, mediaAttachments = emptyList(), poll = poll)

    @Test fun mainProfileOffersTheSameMenuAndCanVote() = verifyEntry(standalone = false)
    @Test fun standaloneProfileOffersTheSameMenuAndCanVote() = verifyEntry(standalone = true)

    private fun verifyEntry(standalone: Boolean) {
        val votes = mutableListOf<Set<Int>>()
        val links = mutableListOf<String>()
        val repository = object : TimelineRepository {
            override suspend fun getHomeTimeline(session: AccountSession, maxId: String?, limit: Int) =
                Result.success(TimelinePage(emptyList(), null, true))
            override suspend fun getProfileHeader(session: AccountSession, accountId: String) = Result.success(
                UserProfile(author, "", "", 0, 0, 1, emptyList(), url = "https://fixture.example/@me", isOwnProfile = true))
            override suspend fun getProfileStatuses(session: AccountSession, accountId: String,
                tab: ProfileStatusTab, maxId: String?) = Result.success(TimelinePage(listOf(post), null, true))
            override suspend fun votePoll(session: AccountSession, statusId: String, pollId: String,
                choices: Set<Int>): Result<TimelineStatus> {
                votes += choices
                return Result.success(post.copy(poll = poll.copy(voted = true, ownVotes = choices, votesCount = 1)))
            }
        }
        val store = ViewModelStore()
        lateinit var model: ProfileViewModel
        rule.runOnIdle {
            model = ProfileViewModel(repository, BrowsingSession().apply { activate(session) },
                accountId = if (standalone) session.accountId else null)
            store.put("profile", model)
        }
        try {
            rule.setContent {
                MastodonClientTheme {
                    if (standalone) {
                        AccountProfileScreen(model, false, AppPreferences(), {}, {}, {}, { _, _ -> },
                            { links += it }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
                    } else {
                        val snackbar = remember { SnackbarHostState() }
                        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                            ProfileScreen(model, AppPreferences(), padding, snackbar, {}, {}, { _, _ -> },
                                { links += it }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { model.uiState.value.profileTabs[ProfileStatusTab.Posts]?.isLoaded == true }
            rule.onNodeWithContentDescription("プロフィールのその他メニュー").performClick()
            rule.onNodeWithText("リンクをコピー").assertDoesNotExist()
            rule.onNodeWithText("QRコードを表示").assertDoesNotExist()
            rule.onNodeWithText("フォロー中のハッシュタグ").assertDoesNotExist()
            val share = rule.onNodeWithText("共有").fetchSemanticsNode().boundsInRoot
            val menuItems = rule.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).fetchSemanticsNodes()
            assertTrue(menuItems.isNotEmpty())
            assertEquals(menuItems.minOf { it.boundsInRoot.top }, share.top, 1f)
            val name = if (standalone) "profile-separate-menu.png" else "profile-main-menu.png"
            File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
                rule.onNode(isPopup()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            rule.onNodeWithText("ブラウザーで開く").performClick()
            rule.runOnIdle { assertEquals(listOf("https://fixture.example/@me"), links) }
            rule.onNodeWithText("First option").performScrollTo().performClick()
            rule.onNodeWithText("投票する").performScrollTo().performClick()
            rule.waitUntil(5_000) { votes.isNotEmpty() }
            rule.runOnIdle {
                assertEquals(listOf(setOf(0)), votes)
                assertTrue(model.uiState.value.profile!!.statuses.single().poll!!.voted == true)
            }
        } finally { rule.runOnIdle { store.clear() } }
    }
}
