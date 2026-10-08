package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.preferences.*
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AccountDataRemovalDeviceTest {
    @Test fun removesAccountFilesNotificationsAndHistoryWithoutTouchingAnotherAccount() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun account() = AccountSession(UUID.randomUUID().toString(), "https://test.example", UUID.randomUUID().toString(), "test", "Test", "", "synthetic-token")
        val a = account(); val b = account()
        var registered = listOf(a, b)
        val job = SupervisorJob()
        val preferenceFile = File(context.cacheDir, "removal-${UUID.randomUUID()}.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { preferenceFile })
        val preferences = UserPreferencesStore(data, isAccountPresent = { id -> registered.any { it.sessionId == id } })
        val media = DraftMediaDataSource(context) { id -> registered.any { it.sessionId == id } }
        val notifications = SystemNotificationDataSource(context) { registered }
        val cleanup = DefaultAccountDataLocalDataSource(preferences, media, notifications)
        val manager = context.getSystemService(NotificationManager::class.java)
        val seen = context.getSharedPreferences("delivered_notifications", 0)
        val markers = context.getSharedPreferences("notification_poll_markers", 0)
        fun file(uri: String) = File(Uri.parse(uri).path!!)
        val author = StatusAuthor("sender", "Sender", "sender", "")
        try {
            val aMedia = media.importMedia(a.sessionId, listOf("content://io.github.ponpokoo.mastodonclient.test.sharedmedia/jpeg")).attachments.single()
            val unsaved = media.importMedia(a.sessionId, listOf("content://io.github.ponpokoo.mastodonclient.test.sharedmedia/mp3")).attachments.single()
            val bMedia = media.importMedia(b.sessionId, listOf("content://io.github.ponpokoo.mastodonclient.test.sharedmedia/jpeg")).attachments.single()
            preferences.setThemeMode(ThemeMode.Dark)
            preferences.saveDraft(ComposeDraft("a", a.sessionId, text = "private-a", attachmentUris = listOf(aMedia.uri)))
            preferences.saveDraft(ComposeDraft("b", b.sessionId, text = "private-b", attachmentUris = listOf(bMedia.uri)))
            for (session in registered) {
                preferences.recordReaction(session.sessionId, "emoji")
                notifications.writePollingMarker(session, "one")
                notifications.showNotification(session, TimelineNotification("one", "favourite", "", author, null))
            }
            val tags = setOf("${a.sessionId}:one", "${b.sessionId}:one")
            withTimeout(5_000) { while (manager.activeNotifications.count { it.tag in tags } != 2) delay(25) }
            registered = listOf(b)
            cleanup.deleteAccount(a.sessionId)
            withTimeout(5_000) { while (manager.activeNotifications.any { it.tag == "${a.sessionId}:one" }) delay(25) }
            assertTrue(manager.activeNotifications.any { it.tag == "${b.sessionId}:one" })
            assertFalse(file(aMedia.uri).exists()); assertFalse(file(unsaved.uri).exists())
            assertTrue(file(bMedia.uri).exists())
            assertEquals(ThemeMode.Dark, preferences.preferences.first().themeMode)
            assertEquals(listOf(b.sessionId), preferences.drafts.first().map { it.sessionId })
            assertEquals(setOf(b.sessionId), preferences.reactionHistory.first().keys)
            assertFalse(seen.contains(a.sessionId)); assertTrue(seen.contains(b.sessionId))
            assertFalse(markers.contains("last_notification_${a.sessionId}"))
            // A polling response or attachment import completing after removal cannot restore data.
            notifications.showNotification(a, TimelineNotification("late", "favourite", "", author, null))
            notifications.writePollingMarker(a, "late")
            assertFalse(markers.contains("last_notification_${a.sessionId}"))
            assertFalse(seen.contains(a.sessionId))
            assertTrue(manager.activeNotifications.none { it.tag == "${a.sessionId}:late" })
            assertTrue(runCatching { media.importMedia(a.sessionId, listOf("content://io.github.ponpokoo.mastodonclient.test.sharedmedia/jpeg")) }.isFailure)
            cleanup.deleteAccount(a.sessionId)
            assertTrue(file(bMedia.uri).exists())
        } finally {
            registered = emptyList()
            cleanup.deleteAccount(a.sessionId); cleanup.deleteAccount(b.sessionId)
            job.cancelAndJoin()
            preferenceFile.delete()
        }
    }
}
