package io.github.ponpokoo.mastodonclient.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPollingJobServiceTest {
    @Test
    fun foregroundSettingControlsPeriodicNotificationDisplayOnlyWhileAppIsVisible() {
        assertFalse(canShowPolledNotification(isForeground = true, foregroundNotificationsEnabled = false))
        assertTrue(canShowPolledNotification(isForeground = true, foregroundNotificationsEnabled = true))
        assertTrue(canShowPolledNotification(isForeground = false, foregroundNotificationsEnabled = false))
    }

    @Test
    fun mapsKnownNotificationTypesToJapaneseTitles() {
        assertEquals("ぽんぽこ さんからメンション", notificationTitle("mention", "ぽんぽこ"))
        assertEquals("ぽんぽこ さんがブーストしました", notificationTitle("reblog", "ぽんぽこ"))
        assertEquals("ぽんぽこ さんがリアクションしました", notificationTitle("emoji_reaction", "ぽんぽこ"))
        assertEquals("ぽんぽこ さんがリアクションしました", notificationTitle("pleroma:emoji_reaction", "ぽんぽこ"))
    }

    @Test
    fun mapsUnknownNotificationTypeToFallbackTitle() {
        assertEquals(
            "ぽんぽこ さんから新しい通知があります",
            notificationTitle("future_type", "ぽんぽこ"),
        )
    }
}
