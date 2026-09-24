package io.github.ponpokoo.mastodonclient

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenIntent
import io.github.ponpokoo.mastodonclient.notification.NotificationOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationOpenIntentDeviceTest {
    @Test fun notificationTapsKeepAccountAndStringIdsDistinct() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = NotificationOpenIntent.create(context, "account:東京", "000001234567890123")
        val otherAccount = NotificationOpenIntent.create(context, "account:別", "000001234567890123")
        val otherNotification = NotificationOpenIntent.create(context, "account:東京", "000001234567890124")

        assertEquals(NotificationOpenRequest("account:東京", "000001234567890123"), NotificationOpenIntent.parse(first))
        assertFalse(first.filterEquals(otherAccount))
        assertFalse(first.filterEquals(otherNotification))
        assertNull(NotificationOpenIntent.parse(Intent(context, MainActivity::class.java)))

        NotificationOpenIntent.clear(first)
        assertNull(NotificationOpenIntent.parse(first))

        val olderNotification = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        assertEquals(NotificationOpenRequest("", ""), NotificationOpenIntent.parse(olderNotification))
        NotificationOpenIntent.clear(olderNotification)
        assertNull(NotificationOpenIntent.parse(olderNotification))
    }
}
