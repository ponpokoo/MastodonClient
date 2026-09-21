package io.github.ponpokoo.mastodonclient

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeys
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.PushMessageSource
import io.github.ponpokoo.mastodonclient.data.repository.*
import io.github.ponpokoo.mastodonclient.domain.model.*
import io.github.ponpokoo.mastodonclient.domain.repository.*
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PushNotificationDeviceTest {
    @Test fun encryptedPushUsesKeystoreAndSharedSystemNotificationHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val json = Json { ignoreUnknownKeys = true }
        val fixture = instrumentation.context.assets.open("push/encrypted.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        val session = AccountSession("push-device-${UUID.randomUUID()}", "https://instance.example", "test", "test", "Test", "", "synthetic-token")
        val record = StoredPushRegistration(PushRegistrationGuard.binding(session), "https://relay.example/", "r".repeat(43), "s".repeat(43),
            json.decodeFromJsonElement<WebPushKeys>(fixture.getValue("keys")), "https://relay.example/push/test", PushRegistrationState.ACTIVE)
        val store = EncryptedPushRegistrationStore(context)
        val local = SystemNotificationDataSource(context)
        val presenter = DefaultSystemNotificationRepository(local)
        val receiver = DefaultPushMessageRepository({ listOf(session) }, store, PushMessageSource { _, _, _, _ -> error("No fetch expected") }, presenter)
        val data = fixture.getValue("standard").jsonObject.mapValues { it.value.jsonPrimitive.content } + ("transport" to "inline")
        val id = "123456789012345678901234567890"
        val tag = "${session.sessionId}:$id"
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            store.write(session.sessionId, record)
            assertEquals(record.keys.privateKey, EncryptedPushRegistrationStore(context).read(session.sessionId)!!.keys.privateKey)
            assertEquals(PushReceiveResult.PROCESSED, receiver.receive(data))
            val posted = manager.activeNotifications.single { it.tag == tag }.notification
            assertEquals("新しい通知", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("<b>そのままのテキスト</b>", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            // A later Streaming/polling notification uses exactly the same persisted ID namespace.
            presenter.show(session, TimelineNotification(id, "favourite", "", StatusAuthor("sender", "Different title", "sender", ""), null)) { true }
            assertEquals("新しい通知", manager.activeNotifications.single { it.tag == tag }.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            manager.cancel(tag, 0)
            store.write(session.sessionId, record.copy(state = PushRegistrationState.REMOVING))
            assertEquals(PushReceiveResult.IGNORED, receiver.receive(data))
            assertTrue(manager.activeNotifications.none { it.tag == tag })
        } finally {
            manager.cancel(tag, 0)
            store.remove(session.sessionId)
            context.getSharedPreferences("delivered_notifications", 0).edit().remove(session.sessionId).commit()
        }
    }
}
