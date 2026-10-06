package io.github.ponpokoo.mastodonclient

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import io.github.ponpokoo.mastodonclient.notification.FcmReceiveWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in observer. Does not fabricate messages, replace subscriptions, or print credentials. */
class PushLiveDeliveryDeviceTest {
    @Test fun captureExistingRegistrationForUpgrade() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePush") == "migrationBaseline")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = checkNotNull(SecureAuthStore(context).getSession())
        val record = checkNotNull(EncryptedPushRegistrationStore(context).read(session.sessionId))
        assertEquals(PushRegistrationState.ACTIVE, record.state)
        // Keep only a digest in app-private storage. Never expose credentials.
        assertTrue(context.getSharedPreferences("push_upgrade_test", 0).edit()
            .putString("identity", identityDigest(record)).commit())
        instrumentation.sendStatus(0, Bundle().apply { putString("live_push", "upgrade_baseline_saved") })
    }

    @Test fun applicationStartupMigratesExistingRegistration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePush") == "migrationCheck")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val baseline = context.getSharedPreferences("push_upgrade_test", 0).getString("identity", null)
        assertNotNull("Capture the old registration before upgrading", baseline)
        val session = checkNotNull(SecureAuthStore(context).getSession())
        // Application.onCreate schedules the normal token sync; this test only observes it.
        withTimeout(120_000) {
            while (true) {
                val record = EncryptedPushRegistrationStore(context).read(session.sessionId)
                if (record?.state == PushRegistrationState.ACTIVE && record.serverKey != null && record.revision > 0) {
                    assertEquals("Upgrade changed the existing registration identity", baseline, identityDigest(record))
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("live_push", "startup_migration_preserved_identity_and_bound_key")
                    })
                    break
                }
                delay(500)
            }
        }
    }

    private fun identityDigest(record: io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration): String {
        val material = listOf(record.credentialBinding, record.relayIdentity, record.registrationId,
            record.managementToken, record.endpoint, record.keys.publicKey, record.keys.privateKey, record.keys.authSecret)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun inspectCurrentPushReadiness() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePush") == "preflight")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val auth = SecureAuthStore(context)
        val session = auth.getSession()
        val registration = session?.let { EncryptedPushRegistrationStore(context).read(it.sessionId) }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("live_push", "preflight")
            putString("session_count", auth.getSessions().size.toString())
            putString("target_account", session?.let { "${it.username}@${it.instanceUrl.toHttpUrl().host}" } ?: "none")
            putString("push_state", registration?.state?.name ?: "none")
            // The installed target may still use the v1 model. Observe it without upgrading
            // the app or changing the user's active subscription as part of this check.
            putString("supports_bound_registration", registration?.javaClass?.methods
                ?.any { it.name == "getServerKey" }?.toString() ?: "none")
            putString("firebase_configured", BuildConfig.FIREBASE_CONFIGURED.toString())
        })
        assertNotNull("No signed-in account on the connected target", session)
        assertNotNull("No saved Push subscription for the current account", registration)
        assertEquals("Current Push subscription is not active", PushRegistrationState.ACTIVE, registration?.state)
    }

    @Test fun inspectOnePostMigrationNotification() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePush") == "migrationDelivery")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = checkNotNull(SecureAuthStore(context).getSession())
        val record = checkNotNull(EncryptedPushRegistrationStore(context).read(session.sessionId))
        assertEquals(PushRegistrationState.ACTIVE, record.state)
        assertNotNull("Registration has no bound server key", record.serverKey)
        val marker = checkNotNull(InstrumentationRegistry.getArguments().getString("expectedText"))
        val after = checkNotNull(InstrumentationRegistry.getArguments().getString("postedAfter")).toLong()
        val manager = context.getSystemService(NotificationManager::class.java)
        withTimeout(90_000) {
            while (true) {
                val displayed = manager.activeNotifications.firstOrNull {
                    it.postTime >= after && it.tag?.startsWith("${session.sessionId}:") == true &&
                        listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).any { key ->
                            it.notification.extras.getCharSequence(key)?.contains(marker) == true
                        }
                }
                if (displayed != null) {
                    assertTrue(!displayed.notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("live_push", "post_migration_notification_displayed")
                        putString("notification_post_time", displayed.postTime.toString())
                    })
                    break
                }
                delay(500)
            }
        }
    }

    @Test fun actualFcmWorkDecryptsAndDisplaysOneNewNotification() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePush") == "observe")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = SecureAuthStore(context).getSession()
        assertNotNull("No signed-in account on the connected target", session)
        val registration = EncryptedPushRegistrationStore(context).read(session!!.sessionId)
        assertEquals("Current Push subscription is not active", PushRegistrationState.ACTIVE, registration?.state)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Android notifications are disabled", manager.areNotificationsEnabled())
        val work = WorkManager.getInstance(context)
        val tag = FcmReceiveWorker::class.java.name
        val baselineWork = work.getWorkInfosByTag(tag).get().map { it.id }.toSet()
        val baselineNotifications = manager.activeNotifications.map { it.key }.toSet()
        val expectedText = InstrumentationRegistry.getArguments().getString("expectedText")
        instrumentation.sendStatus(0, Bundle().apply { putString("live_push", "ready_for_one_external_notification") })
        withTimeout(180_000) {
            while (true) {
                val received = work.getWorkInfosByTag(tag).get().any {
                    it.id !in baselineWork && it.state == WorkInfo.State.SUCCEEDED
                }
                val displayed = manager.activeNotifications.firstOrNull {
                    it.key !in baselineNotifications && it.tag?.startsWith("${session.sessionId}:") == true &&
                        (expectedText.isNullOrBlank() || listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                            .any { key -> it.notification.extras.getCharSequence(key)?.contains(expectedText) == true })
                }
                if (received && displayed != null) {
                    assertTrue("Decrypted notification has no title", !displayed.notification.extras
                        .getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("live_push", "real_fcm_work_succeeded_and_notification_displayed")
                    })
                    break
                }
                delay(500)
            }
        }
    }
}
