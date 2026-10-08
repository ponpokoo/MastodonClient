package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeys
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.PushMessageSource
import io.github.ponpokoo.mastodonclient.data.remote.PushSyncSource
import io.github.ponpokoo.mastodonclient.data.repository.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.notification.FcmWorkScheduler
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class HybridPushDeviceTest {
    @Test fun hybridReceiveRestoresEncryptedCursorAndDeduplicatesInlineNotifications() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val json = Json { ignoreUnknownKeys = true }
        val fixture = instrumentation.context.assets.open("push/encrypted.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        val session = AccountSession("hybrid-device-${UUID.randomUUID()}", "https://example.test", "me", "me", "Me", "", "synthetic-token")
        val store = EncryptedPushRegistrationStore(context)
        val local = SystemNotificationDataSource(context) { listOf(session) }
        val manager = context.getSystemService(NotificationManager::class.java)
        val inlineId = "123456789012345678901234567890"
        val newerId = "opaque-hybrid-new"
        val actor = StatusAuthor("actor", "Actor", "actor", "")
        var calls = 0
        val scheduled = mutableListOf<Pair<String, String>>()
        val sync = DefaultPushSyncRepository({ listOf(session) }, store, PushSyncSource { _, since, max ->
            calls++; assertEquals("opaque-start", since)
            if (max == null) listOf(TimelineNotification(newerId, "mention", "", actor, null),
                TimelineNotification(inlineId, "favourite", "", actor, null)) else emptyList()
        }, SyncedNotificationPresenter { account, notification, current -> local.showNotification(account, notification, current) },
            { accountId, id -> scheduled += accountId to id })
        val receiver = DefaultPushMessageRepository({ listOf(session) }, store,
            PushMessageSource { _, _, _, _ -> error("Hybrid receive must not fetch Relay bodies") },
            DefaultSystemNotificationRepository(local), requestSync = sync::requestRegistration)
        try {
            store.write(session.sessionId, StoredPushRegistration(PushRegistrationGuard.binding(session), "https://relay.test/",
                "r".repeat(43), "management", json.decodeFromJsonElement<WebPushKeys>(fixture.getValue("keys")),
                "https://relay.test/push/test", PushRegistrationState.ACTIVE, syncInitialized = true, syncSinceId = "opaque-start"))
            val inline = fixture.getValue("standard").jsonObject.mapValues { it.value.jsonPrimitive.content } + ("transport" to "inline")
            assertEquals(PushReceiveResult.PROCESSED, receiver.receive(inline))
            val trigger = mapOf("version" to "2", "registrationId" to "r".repeat(43), "messageId" to "m".repeat(43), "transport" to "sync_required")
            repeat(5) { assertEquals(PushReceiveResult.PROCESSED, receiver.receive(trigger)) }
            assertTrue(scheduled.all { it.first == session.sessionId && it.second == "r".repeat(43) })
            repeat(5) { sync.sync(session.sessionId, "r".repeat(43)) }
            assertEquals(2, calls)
            val restored = EncryptedPushRegistrationStore(context).read(session.sessionId)!!
            assertEquals(newerId, restored.syncSinceId); assertEquals(restored.syncRequested, restored.syncCompleted)
            assertTrue(restored.syncDeliveredIds.isEmpty())
            val active = manager.activeNotifications.filter { it.tag?.startsWith("${session.sessionId}:") == true }
            assertEquals(2, active.count { it.tag == "${session.sessionId}:$inlineId" || it.tag == "${session.sessionId}:$newerId" })
            assertEquals("新しい通知", active.single { it.tag == "${session.sessionId}:$inlineId" }
                .notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        } finally {
            local.dismissForAccount(session.sessionId)
            store.remove(session.sessionId)
            context.getSharedPreferences("delivered_notifications", 0).edit().remove(session.sessionId).commit()
        }
    }

    @Test fun accountWorkIsDurableAndAnUnknownAccountFinishesWithoutNetworkRequests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionId = "hybrid-work-${UUID.randomUUID()}"
        val work = WorkManager.getInstance(context)
        try {
            FcmWorkScheduler.syncAccount(context, sessionId, "r".repeat(43))
            val oldIds = work.getWorkInfosForUniqueWork("nagisa-push-sync-$sessionId").get(5, TimeUnit.SECONDS).map { it.id }.toSet()
            FcmWorkScheduler.cancelSync(context, sessionId)
            assertTrue(work.getWorkInfosForUniqueWork("nagisa-push-sync-$sessionId").get(5, TimeUnit.SECONDS)
                .filter { it.id in oldIds }.all { it.state == WorkInfo.State.CANCELLED })
            repeat(2) { FcmWorkScheduler.syncAccount(context, sessionId, "r".repeat(43)) }
            withTimeout(30_000) {
                while (true) {
                    val infos = work.getWorkInfosForUniqueWork("nagisa-push-sync-$sessionId").get(5, TimeUnit.SECONDS)
                    val newWork = infos.filter { it.id !in oldIds }
                    if (newWork.size == 2 && newWork.all { it.state == WorkInfo.State.SUCCEEDED }) break
                    delay(250)
                }
            }
        } finally { work.cancelUniqueWork("nagisa-push-sync-$sessionId").result.get(5, TimeUnit.SECONDS) }
    }
}
