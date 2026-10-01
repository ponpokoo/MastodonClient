package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.StatusAuthor
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SystemNotificationDismissalDeviceTest {
    @Test fun dismissingOneAccountKeepsOtherAccountsNotifications() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val first = AccountSession("first-${UUID.randomUUID()}", "https://first.example", "first", "first", "First", "", "test-token")
        val second = AccountSession("second-${UUID.randomUUID()}", "https://second.example", "second", "second", "Second", "", "test-token")
        val local = SystemNotificationDataSource(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val tags = listOf("${first.sessionId}:one", "${first.sessionId}:two", "${second.sessionId}:three")
        val author = StatusAuthor("sender", "Sender", "sender", "")
        suspend fun awaitTags(expected: Set<String>) {
            withTimeout(5_000) {
                while (manager.activeNotifications.mapNotNull { it.tag }.filter { it in tags }.toSet() != expected) delay(25)
            }
        }
        try {
            local.showNotification(first, TimelineNotification("one", "favourite", "", author, null))
            local.showNotification(first, TimelineNotification("two", "favourite", "", author, null))
            local.showNotification(second, TimelineNotification("three", "favourite", "", author, null))
            awaitTags(tags.toSet())
            assertEquals(tags.toSet(), manager.activeNotifications.mapNotNull { it.tag }.filter { it in tags }.toSet())

            local.dismissRead(first.sessionId, setOf("one"))
            awaitTags(setOf(tags[1], tags[2]))
            assertTrue(manager.activeNotifications.none { it.tag == tags[0] })
            assertTrue(manager.activeNotifications.any { it.tag == tags[1] })
            assertTrue(manager.activeNotifications.any { it.tag == tags[2] })
            local.showNotification(first, TimelineNotification("one", "favourite", "", author, null))
            assertTrue(manager.activeNotifications.none { it.tag == tags[0] })

            local.dismissForAccount(first.sessionId)
            awaitTags(setOf(tags[2]))

            assertTrue(manager.activeNotifications.none { it.tag == tags[0] || it.tag == tags[1] })
            assertTrue(manager.activeNotifications.any { it.tag == tags[2] })
        } finally {
            tags.forEach { manager.cancel(it, 0) }
            context.getSharedPreferences("delivered_notifications", 0).edit()
                .remove(first.sessionId).remove(second.sessionId).commit()
        }
    }
}
