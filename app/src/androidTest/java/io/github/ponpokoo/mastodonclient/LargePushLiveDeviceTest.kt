package io.github.ponpokoo.mastodonclient

import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.media.AudioManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeys
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import io.github.ponpokoo.mastodonclient.data.remote.PushMessageSource
import io.github.ponpokoo.mastodonclient.data.remote.PushSyncSource
import io.github.ponpokoo.mastodonclient.data.remote.RelayRegistrationDataSource
import io.github.ponpokoo.mastodonclient.data.repository.DefaultPushMessageRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultPushSyncRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultSystemNotificationRepository
import io.github.ponpokoo.mastodonclient.data.repository.MastodonPushSyncSource
import io.github.ponpokoo.mastodonclient.data.repository.PushRegistrationGuard
import io.github.ponpokoo.mastodonclient.data.repository.SyncedNotificationPresenter
import io.github.ponpokoo.mastodonclient.domain.model.hasSameCredentials
import io.github.ponpokoo.mastodonclient.domain.repository.PushReceiveResult
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import io.github.ponpokoo.mastodonclient.notification.FcmReceiveWorker
import io.github.ponpokoo.mastodonclient.notification.SystemNotificationDataSource
import io.github.ponpokoo.mastodonclient.notification.currentFcmToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit opt-in: one disposable Relay registration, one real FCM send, and read-only Mastodon API calls.
 * The received envelope is dispatched through isolated repositories; normal subscriptions are untouched.
 */
class LargePushLiveDeviceTest {
    @Test fun fourKiBTrialArrivesViaFcmAndAnIsolatedSyncDisplaysAnApiNotification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("largePushLive") == "true")
        val context = instrumentation.targetContext
        assertTrue("A configured Debug build is required", BuildConfig.DEBUG && BuildConfig.FIREBASE_CONFIGURED)
        assertTrue("A configured Relay is required", BuildConfig.RELAY_URL.isNotBlank())
        assertEquals("Mute notification audio before running this trial", 0,
            context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Android notifications must be enabled", manager.areNotificationsEnabled())
        val auth = SecureAuthStore(context)
        val original = checkNotNull(auth.getSession()) { "A signed-in account is required" }
        val trial = original.copy(sessionId = "large-push-live-${UUID.randomUUID()}")
        val sessions = suspend {
            auth.getSessions().filter { original.hasSameCredentials(it) }.map { trial }
        }
        val store = EncryptedPushRegistrationStore(context)
        val local = SystemNotificationDataSource(context, sessions)
        val json = Json { ignoreUnknownKeys = true }
        val api = MastodonPushSyncSource(ApiClientFactory())
        val history = protectedCall("Mastodon preflight failed") { api.page(original, null, null) }
        assertTrue("The selected account needs at least one notification for this read-only trial", history.isNotEmpty())
        val expected = history.first()
        val lowerBound = history.getOrNull(1)?.id
        val generator = WebPushKeyGenerator()
        val vapid = generator.generate()
        val registrationId = generator.randomSecret()
        val management = generator.randomSecret()
        val relay = RelayRegistrationDataSource(BuildConfig.RELAY_URL)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(30, TimeUnit.SECONDS).build()
        val work = WorkManager.getInstance(context)
        var registrationAttempted = false
        val metrics = JSONObject().put("scope", "real_fcm_isolated_dispatch_real_api")
            .put("trial_body_bytes", 4096).put("normal_subscription_modified", false)
        try {
            val capabilities = withContext(Dispatchers.IO) {
                protectedCall("Relay capability request failed") {
                    client.newCall(Request.Builder().url(BuildConfig.RELAY_URL.toHttpUrl()
                        .newBuilder().addPathSegment("v2").addPathSegment("capabilities").build()).build())
                        .execute().use { response ->
                            check(response.isSuccessful) { "Relay capability request was rejected" }
                            JSONObject(response.body!!.string())
                        }
                }
            }
            assertTrue("The configured Relay must support hybrid delivery", capabilities.optBoolean("syncRequired"))
            val fcmToken = protectedCall("Firebase token request failed") { withTimeout(60_000) { currentFcmToken() } }
            registrationAttempted = true
            val destination = protectedCall("Trial registration failed") {
                relay.putBound(registrationId, management, fcmToken, vapid.publicKey, 1)
            }
            store.write(trial.sessionId, StoredPushRegistration(PushRegistrationGuard.binding(trial),
                BuildConfig.RELAY_URL, registrationId, management, generator.generate(), destination.endpoint,
                PushRegistrationState.ACTIVE, serverKey = vapid.publicKey, revision = 1,
                syncInitialized = lowerBound != null, syncSinceId = lowerBound))
            val receiptId = withContext(Dispatchers.IO) {
                protectedCall("Trial Push request failed") {
                    client.newCall(Request.Builder().url(destination.endpoint)
                        .header("Authorization", authorization(BuildConfig.RELAY_URL, vapid))
                        .header("Content-Encoding", "aes128gcm").header("TTL", "120")
                        .post(ByteArray(4096).toRequestBody("application/octet-stream".toMediaType())).build())
                        .execute().use { response ->
                            check(response.code == 201) { "Trial Push was rejected (HTTP ${response.code})" }
                            checkNotNull(response.header("Location")?.substringAfterLast('/')) { "Missing trial receipt" }
                        }
                }
            }
            val received = withTimeout(120_000) {
                var envelope: Map<String, String>? = null
                while (envelope == null) {
                    envelope = receivedEnvelope(context.noBackupFilesDir, registrationId, receiptId, json)
                    if (envelope == null) delay(250)
                }
                envelope
            }
            assertEquals("sync_required", received["transport"])
            assertEquals("2", received["version"])
            assertEquals(setOf("version", "registrationId", "messageId", "transport"), received.keys)
            metrics.put("transport", received.getValue("transport"))
                .put("fcm_data_json_bytes", json.encodeToString(received).toByteArray().size)
            var apiCalls = 0
            var displayed = 0
            var scheduled = 0
            val sync = DefaultPushSyncRepository(sessions, store, PushSyncSource { session, since, max ->
                apiCalls++
                protectedCall("Mastodon synchronization failed") { api.page(session, since, max) }
            }, SyncedNotificationPresenter { session, notification, current ->
                if (notification.id == expected.id) {
                    local.showNotification(session, notification, current)
                    displayed++
                }
            }, { sessionId, id ->
                assertTrue("Sync was scheduled for a different trial", sessionId == trial.sessionId && id == registrationId)
                scheduled++
            })
            val receiver = DefaultPushMessageRepository(sessions, store,
                PushMessageSource { _, _, _, _ -> error("Large Push must not fetch Relay bodies") },
                DefaultSystemNotificationRepository(local), requestSync = sync::requestRegistration)
            // The global worker ignores this isolated registration. Dispatch the actual received data here.
            assertEquals(PushReceiveResult.PROCESSED, receiver.receive(received))
            sync.sync(trial.sessionId, registrationId)
            sync.sync(trial.sessionId, registrationId)
            assertEquals(1, scheduled)
            assertEquals(1, displayed)
            assertEquals(1, manager.activeNotifications.count { it.tag == "${trial.sessionId}:${expected.id}" })
            val checkpoint = checkNotNull(store.read(trial.sessionId))
            assertEquals(checkpoint.syncRequested, checkpoint.syncCompleted)
            metrics.put("api_sync_requests", apiCalls).put("displayed_notifications", displayed)
                .put("isolated_dispatch", true).put("duplicate_display", false)
            withTimeout(30_000) {
                while (work.getWorkInfosForUniqueWork("nagisa-push-$registrationId-$receiptId").get()
                        .none { it.state == WorkInfo.State.SUCCEEDED }) delay(250)
            }
            metrics.put("receive_worker_succeeded", true)
        } finally {
            local.dismissForAccount(trial.sessionId)
            store.remove(trial.sessionId)
            context.getSharedPreferences("delivered_notifications", 0).edit().remove(trial.sessionId).commit()
            if (registrationAttempted) {
                protectedCall("Trial Relay registration cleanup failed") { relay.remove(registrationId, management) }
                metrics.put("trial_registration_removed", true)
            }
        }
        val result = File(checkNotNull(context.externalCacheDir), "large-push-live.json")
        result.writeText(metrics.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("anonymous_large_push", metrics.toString()) })
    }

    private fun receivedEnvelope(directory: File, registration: String, receipt: String, json: Json): Map<String, String>? {
        val file = File(directory, "androidx.work.workdb")
        if (!file.isFile) return null
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT input FROM workspec WHERE worker_class_name = ?", arrayOf(FcmReceiveWorker::class.java.name))
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val encoded = Data.fromByteArray(cursor.getBlob(0)).getString("envelope") ?: continue
                        val data = runCatching { json.decodeFromString<Map<String, String>>(encoded) }.getOrNull() ?: continue
                        if (data["registrationId"] == registration && data["messageId"] == receipt) return@use data
                    }
                    null
                }
        }
    }

    private fun authorization(base: String, keys: WebPushKeys): String {
        val origin = base.toHttpUrl().newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = encode("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".toByteArray())
        val claims = encode(JSONObject().put("aud", origin).put("exp", System.currentTimeMillis() / 1000 + 300)
            .put("sub", "mailto:test@example.test").toString().toByteArray())
        val signingInput = "$header.$claims"
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(keys.privateKey)))
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(signingInput.toByteArray()); sign()
        }
        var position = 0
        fun byte() = der[position++].toInt() and 255
        check(byte() == 0x30 && byte() == der.size - 2) { "Invalid trial signature" }
        fun integer(): ByteArray {
            check(byte() == 2) { "Invalid trial signature" }
            val length = byte()
            val value = der.copyOfRange(position, position + length).dropWhile { it == 0.toByte() }.toByteArray()
            position += length
            check(value.size <= 32) { "Invalid trial signature" }
            return ByteArray(32 - value.size) + value
        }
        val signature = integer() + integer()
        check(position == der.size) { "Invalid trial signature" }
        return "vapid t=$signingInput.${encode(signature)}, k=${keys.publicKey}"
    }

    /** Transport exceptions can contain delivery URLs; expose only the fixed trial phase. */
    private suspend fun <T> protectedCall(message: String, action: suspend () -> T): T = try {
        action()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        throw AssertionError(message)
    }
}
