package io.github.ponpokoo.mastodonclient.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPollingJobServiceTest {
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
