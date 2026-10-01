package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.AppPreferences
import io.github.ponpokoo.mastodonclient.core.preferences.AvatarIconShape
import io.github.ponpokoo.mastodonclient.core.preferences.TimelineDisplayPreferences
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.feature.notifications.NotificationsUiState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileUiState
import io.github.ponpokoo.mastodonclient.feature.timeline.NotificationFilter
import io.github.ponpokoo.mastodonclient.feature.timeline.NotificationsContent
import io.github.ponpokoo.mastodonclient.feature.timeline.ProfileContent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ProfileNotificationAvatarDeviceTest {
    @get:Rule val rule = createComposeRule()

    private fun author(): StatusAuthor {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "avatar-shape-test.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return StatusAuthor("author", "テストユーザー", "test@example.test", image.toURI().toString())
    }

    @Test fun profileAvatarAndItsBorderFollowShapeWithoutBreakingTap() {
        val author = author()
        val preferences = mutableStateOf(AppPreferences())
        var avatarOpened = false
        rule.setContent {
            MaterialTheme {
                ProfileContent(
                    state = ProfileUiState(profile = UserProfile(author, "", "", 0, 0, 0, emptyList())),
                    padding = PaddingValues(), onRetry = {}, onRefresh = {}, onStatusClick = {},
                    onOpenLink = {}, onReply = {}, onBoost = {}, onQuote = { _, _ -> }, onFavourite = {},
                    onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    preferences = preferences.value, onAvatarClick = { avatarOpened = true },
                )
            }
        }
        assertShape("profile_avatar", square = false)
        rule.runOnIdle { preferences.value = squarePreferences() }
        assertShape("profile_avatar", square = true)
        rule.onNodeWithTag("profile_avatar").performClick()
        rule.runOnIdle {
            assertEquals(true, avatarOpened)
            preferences.value = AppPreferences()
        }
        assertShape("profile_avatar", square = false)
    }

    @Test fun notificationActorAndQuotedAuthorBothFollowShape() {
        val author = author()
        val preferences = mutableStateOf(AppPreferences())
        val status = TimelineStatus(
            timelineId = "post", statusId = "post", createdAt = "2026-10-01T00:00:00Z",
            author = author, boostedBy = null, contentHtml = "<p>本文</p>", spoilerText = "",
            sensitive = false, visibility = "public", url = null, repliesCount = 0,
            boostsCount = 0, favouritesCount = 0, mediaAttachments = emptyList(),
        )
        rule.setContent {
            MaterialTheme {
                NotificationsContent(
                    state = NotificationsUiState(notifications = listOf(
                        TimelineNotification("notification", "favourite", status.createdAt, author, status),
                    ), notificationsEndReached = true),
                    padding = PaddingValues(), onRefresh = {}, onLoadMore = {}, onStatusClick = {},
                    onOpenLink = {}, onReply = {}, onBoost = {}, onFavourite = {}, onBookmark = {},
                    onReact = { _, _ -> }, onAccountClick = {}, onMediaClick = { _, _ -> },
                    preferences = preferences.value,
                    listStates = List(3) { rememberLazyListState() }, selectedFilter = NotificationFilter.All,
                    onSelectFilter = {}, newNoticeMessage = null, onNewNoticeClick = null,
                )
            }
        }
        val tags = listOf("notification_actor_avatar", "notification_status_author_avatar")
        tags.forEach { assertShape(it, square = false) }
        rule.runOnIdle { preferences.value = squarePreferences() }
        tags.forEach { assertShape(it, square = true) }
        rule.runOnIdle { preferences.value = AppPreferences() }
        tags.forEach { assertShape(it, square = false) }
    }

    private fun squarePreferences() = AppPreferences(
        timelineDisplay = TimelineDisplayPreferences(avatarIconShape = AvatarIconShape.Square),
    )

    private fun assertShape(tag: String, square: Boolean) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true)
        rule.waitUntil(10_000) {
            val pixels = node.captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2] == Color.Red
        }
        val pixels = node.captureToImage().toPixelMap()
        val corner = pixels[pixels.width / 10, pixels.height / 10]
        if (square) assertEquals(Color.Red, corner) else assertNotEquals(Color.Red, corner)
    }
}
