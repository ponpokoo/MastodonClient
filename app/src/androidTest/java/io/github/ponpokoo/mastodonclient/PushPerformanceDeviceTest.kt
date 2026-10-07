package io.github.ponpokoo.mastodonclient

import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.net.TrafficStats
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.core.security.WebPushDecryptor
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import io.github.ponpokoo.mastodonclient.data.remote.dto.RelayMessageDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.WebPushNotificationDto
import io.github.ponpokoo.mastodonclient.data.repository.MastodonPushSyncSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Device-local, explicit opt-in. Exports only sizes, durations, counts and static labels. */
class PushPerformanceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun preflight() = runBlocking {
        assumeTrue(arguments.getString("pushPerformance") == "preflight")
        val auth = SecureAuthStore(context)
        val session = auth.getSession()
        val record = session?.let { EncryptedPushRegistrationStore(context).read(it.sessionId) }
        status("preflight", JSONObject().put("session_count", auth.getSessions().size)
            .put("active_subscription", record?.state == PushRegistrationState.ACTIVE)
            .put("notifications_enabled", manager.areNotificationsEnabled())
            .put("firebase_configured", BuildConfig.FIREBASE_CONFIGURED)
            .put("debug_timestamps", BuildConfig.DEBUG))
        assertTrue("A signed-in account with active Push is required", session != null && record?.state == PushRegistrationState.ACTIVE)
        assertTrue("Android notifications must be enabled", manager.areNotificationsEnabled())
    }

    @Test fun inspectRecentDisplayLatency() = runBlocking {
        assumeTrue(arguments.getString("pushPerformance") == "history")
        val (session, _) = target()
        val after = arguments.getString("postedAfter")?.toLongOrNull() ?: System.currentTimeMillis() - 30 * 60_000
        val posted = displayed(session).filterValues { it >= after }.entries.sortedByDescending { it.value }.take(5)
            .associate { it.key to it.value }
        val result = JSONObject().put("scope", "retained_display_times_no_traffic_baseline")
            .put("matched_display_count", posted.size)
        val probe = ApiProbe()
        val notifications = probe.source.page(session, null, null)
        val latency = notifications.mapNotNull { item ->
            posted[item.id]?.let { time -> runCatching { time - Instant.parse(item.createdAt).toEpochMilli() }.getOrNull() }
        }
        result.put("server_notification_to_os_post_ms", JSONArray(latency))
            .put("timestamp_matches", latency.size).put("probe_api_requests", probe.calls)
            .put("probe_response_body_bytes", probe.responseBytes)
        save("history", result)
    }

    @Test fun inspectClockOffset() = runBlocking {
        assumeTrue(arguments.getString("pushPerformance") == "clock")
        // Run after the traffic window. HTTP Date has one-second precision; keep bounds,
        // not a misleading exact correction. No response body, IP or account data is exported.
        val client = OkHttpClient.Builder().build()
        val samples = mutableListOf<JSONObject>()
        repeat(8) {
            val request = Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace?measurement=${UUID.randomUUID()}")
                .header("Cache-Control", "no-cache").build()
            val before = System.currentTimeMillis()
            val monotonic = SystemClock.elapsedRealtime()
            client.newCall(request).execute().use { response ->
                val after = System.currentTimeMillis()
                val reference = ZonedDateTime.parse(requireNotNull(response.header("Date")), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
                response.body?.close()
                check(response.isSuccessful && (response.header("Age")?.toLongOrNull() ?: 0) == 0L)
                samples += JSONObject().put("rtt_ms", SystemClock.elapsedRealtime() - monotonic)
                    .put("reference_minus_device_min_ms", reference - after)
                    .put("reference_minus_device_max_ms", reference + 1000 - before)
            }
            delay(173)
        }
        val low = samples.maxOf { it.getLong("reference_minus_device_min_ms") }
        val high = samples.minOf { it.getLong("reference_minus_device_max_ms") }
        val result = JSONObject().put("scope", "post_window_https_date_clock_check")
            .put("reference", "cloudflare_public_https_date").put("date_resolution_ms", 1000)
            .put("reference_minus_device_min_ms", low).put("reference_minus_device_max_ms", high)
            .put("consistent_bounds", low <= high).put("samples", JSONArray(samples))
        save("clock", result)
        assertTrue("Clock offset bounds must intersect", low <= high)
    }

    @Test fun measureNewRealPushWindow() = runBlocking {
        assumeTrue(arguments.getString("pushPerformance") == "observe")
        val (session, record) = target()
        assertTrue("Android notifications must be enabled", manager.areNotificationsEnabled())
        assertTrue("Debug receive timestamps are required", BuildConfig.DEBUG)
        val expected = arguments.getString("expectedCount")?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val seconds = arguments.getString("observeSeconds")?.toLongOrNull()?.coerceIn(30, 300) ?: 180
        delay(10_000) // Let startup registration/recovery requests settle before the idle baseline.
        val idleStart = counters()
        val idleTime = SystemClock.elapsedRealtime()
        delay(15_000)
        val idle = delta(idleStart, counters())
        val idleDuration = SystemClock.elapsedRealtime() - idleTime
        val baselineWork = receiveWork().map { it.localId }.toSet()
        val baselineTags = displayed(session).keys
        val baselineTraffic = counters()
        val started = SystemClock.elapsedRealtime()
        val observed = linkedMapOf<String, Received>()
        val firstPosts = linkedMapOf<String, Long>()
        val result = JSONObject().put("scope", "live_fcm_background_window")
            .put("expected_count", expected).put("idle_duration_ms", idleDuration)
            .put("idle_traffic_bytes", trafficJson(idle)).put("poll_interval_ms", 100)
        status("ready_for_external_push", JSONObject().put("expected_count", expected).put("window_seconds", seconds))
        while (SystemClock.elapsedRealtime() - started < seconds * 1000) {
            receiveWork().forEach { received ->
                if (received.localId !in baselineWork && received.envelope["registrationId"] == record.registrationId)
                    observed.putIfAbsent(received.localId, received)
            }
            displayed(session).forEach { (id, time) -> if (id !in baselineTags) firstPosts.putIfAbsent(id, time) }
            if (observed.size >= expected && firstPosts.size >= expected) { delay(5_000); break }
            delay(100)
        }
        val observationDuration = SystemClock.elapsedRealtime() - started
        val traffic = delta(baselineTraffic, counters()) // Before the separate timestamp lookup API request.
        val samples = observed.values.map { received ->
            val transport = received.envelope["transport"].orEmpty()
            val notificationId = if (transport == "inline") inlineId(received.envelope, record) else null
            val postedAt = notificationId?.let(firstPosts::get)
            JSONObject().put("transport", transport)
                .put("fcm_data_json_bytes", json.encodeToString(received.envelope).toByteArray().size)
                .put("ciphertext_bytes", if (transport == "inline") RelayMessageDto.inline(received.envelope).bytes().size else 0)
                .put("sent_to_received_ms", timeDelta(received.sentAt, received.receivedAt))
                .put("received_to_os_post_ms", timeDelta(received.receivedAt, postedAt))
                .put("sent_to_os_post_ms", timeDelta(received.sentAt, postedAt))
                .put("display_matched", postedAt != null)
        }
        result.put("observation_duration_ms", observationDuration).put("received_count", observed.size)
            .put("displayed_count", firstPosts.size).put("traffic_bytes", trafficJson(traffic))
            .put("samples", JSONArray(samples))
        val probe = ApiProbe()
        val apiNotifications = probe.source.page(session, null, null)
        val endToEnd = apiNotifications.mapNotNull { item ->
            firstPosts[item.id]?.let { postedAt -> runCatching { postedAt - Instant.parse(item.createdAt).toEpochMilli() }.getOrNull() }
        }
        result.put("server_notification_to_os_post_ms", JSONArray(endToEnd))
            .put("post_window_probe_api_requests", probe.calls).put("post_window_probe_response_body_bytes", probe.responseBytes)
        save("observe", result)
        assertTrue("Not all requested real FCM messages arrived in the observation window", observed.size >= expected)
        assertTrue("Not all requested notifications were displayed", firstPosts.size >= expected)
    }

    private suspend fun target(): Pair<AccountSession, StoredPushRegistration> {
        val session = checkNotNull(SecureAuthStore(context).getSession()) { "No selected account" }
        val record = checkNotNull(EncryptedPushRegistrationStore(context).read(session.sessionId)) { "No Push subscription" }
        check(record.state == PushRegistrationState.ACTIVE) { "Push subscription is not active" }
        return session to record
    }

    private fun displayed(session: AccountSession): Map<String, Long> = manager.activeNotifications.mapNotNull { notification ->
        notification.tag?.takeIf { it.startsWith("${session.sessionId}:") }
            ?.removePrefix("${session.sessionId}:")?.let { it to notification.postTime }
    }.toMap()

    private data class Received(val localId: String, val envelope: Map<String, String>, val sentAt: Long, val receivedAt: Long) {
        override fun toString() = "Received(redacted)"
    }

    private fun receiveWork(): List<Received> {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        if (!file.isFile) return emptyList()
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,input FROM workspec WHERE worker_class_name = ?",
                arrayOf("io.github.ponpokoo.mastodonclient.notification.FcmReceiveWorker")).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val data = Data.fromByteArray(cursor.getBlob(1))
                        val encoded = data.getString("envelope") ?: continue
                        val envelope = runCatching { json.decodeFromString<Map<String, String>>(encoded) }.getOrNull() ?: continue
                        add(Received(cursor.getString(0), envelope, data.getLong("measurementSentAt", 0), data.getLong("measurementReceivedAt", 0)))
                    }
                }
            }
        }
    }

    private fun inlineId(data: Map<String, String>, record: StoredPushRegistration): String? = runCatching {
        val envelope = RelayMessageDto.inline(data)
        val bytes = WebPushDecryptor().decrypt(record.keys, envelope.encoding, envelope.cryptoHeaders(), envelope.bytes())
        // Match inside the target process only. Neither plaintext nor the ID leaves the device.
        json.decodeFromString<WebPushNotificationDto>(bytes.toString(Charsets.UTF_8)).id
    }.getOrNull()

    private fun timeDelta(start: Long, end: Long?): Any = if (start > 0 && end != null && end > 0) end - start else JSONObject.NULL
    private fun counters() = longArrayOf(TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()),
        TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes())
    private fun delta(before: LongArray, after: LongArray) = LongArray(4) { i -> if (before[i] < 0 || after[i] < before[i]) -1 else after[i] - before[i] }
    private fun trafficJson(values: LongArray) = JSONObject().put("app_rx", values[0]).put("app_tx", values[1])
        .put("device_rx", values[2]).put("device_tx", values[3])

    private class ApiProbe {
        var calls = 0
        var responseBytes = 0L
        val source = MastodonPushSyncSource(ApiClientFactory(OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls++ }
            override fun responseBodyEnd(call: Call, byteCount: Long) { responseBytes += byteCount }
        }).build()))
    }

    private fun save(mode: String, result: JSONObject) {
        File(requireNotNull(context.externalCacheDir), "push-performance-$mode.json").writeText(result.toString())
        status("complete", result)
    }

    private fun status(phase: String, result: JSONObject) = instrumentation.sendStatus(0, Bundle().apply {
        putString("push_performance_phase", phase)
        putString("anonymous_metrics", result.toString())
    })
}
