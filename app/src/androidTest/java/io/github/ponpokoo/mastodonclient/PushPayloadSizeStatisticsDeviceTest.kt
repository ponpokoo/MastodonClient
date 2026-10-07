package io.github.ponpokoo.mastodonclient

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.remote.RelayMessageDataSource
import io.github.ponpokoo.mastodonclient.data.remote.dto.RelayMessageDto
import io.github.ponpokoo.mastodonclient.data.repository.PushRegistrationGuard
import io.github.ponpokoo.mastodonclient.domain.repository.PushRegistrationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, device-local aggregation. Never exports Work data, identifiers, or ciphertext. */
class PushPayloadSizeStatisticsDeviceTest {
    @Test fun observeNewReceiveWorkAndExportOnlySizeCounters() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pushSizeStats") == "observe")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = File(context.noBackupFilesDir, "androidx.work.workdb")
        val auth = SecureAuthStore(context)
        val registrations = EncryptedPushRegistrationStore(context)
        val source = RelayMessageDataSource()
        val resultFile = File(requireNotNull(context.externalCacheDir), "push-size-statistics.json")
        var active = 0
        auth.getSessions().forEach { session ->
            val record = registrations.read(session.sessionId)
            if (record?.state == PushRegistrationState.ACTIVE && record.endpoint != null &&
                record.credentialBinding == PushRegistrationGuard.binding(session)) active++
        }
        val seen = readEnvelopes(database).mapTo(mutableSetOf()) { it.first }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("push_size_statistics", "ready_for_external_push")
            putString("active_subscriptions", active.toString())
        })
        val counts = linkedMapOf("new_receive_work" to 0, "inline" to 0, "fetch" to 0,
            "measured" to 0, "unavailable" to 0, "raw_le_1k" to 0, "raw_k1_2" to 0,
            "raw_k2_3" to 0, "raw_k3_4" to 0, "raw_gt_4k" to 0,
            "json_inline_eligible" to 0, "json_fetch_required" to 0, "probe_fetch_attempts" to 0)
        val seconds = InstrumentationRegistry.getArguments().getString("pushSizeSeconds")?.toLongOrNull()
            ?.coerceIn(10, 180) ?: 180
        fun saveCounters(phase: String) {
            // Only numeric counters and static scope labels are written to this temporary result.
            val result = JSONObject().put("scope", "new_work_only_no_payload_export")
                .put("phase", phase).put("observation_seconds", seconds).put("active_subscriptions", active)
            counts.forEach { (key, value) -> result.put(key, value) }
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(result.toString(), Charsets.UTF_8)
        }
        saveCounters("observing")
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (active > 0 && SystemClock.elapsedRealtime() < until && counts.getValue("new_receive_work") < 50) {
            for ((id, envelope) in readEnvelopes(database)) {
                if (!seen.add(id)) continue
                counts.increment("new_receive_work")
                val data = try {
                    if (envelope?.optString("version") != "1") null else when (envelope.optString("transport")) {
                        "inline" -> {
                            counts.increment("inline")
                            RelayMessageDto.inline(envelope.keys().asSequence().associateWith { envelope.getString(it) })
                        }
                        "fetch" -> {
                            counts.increment("fetch")
                            val registrationId = envelope.getString("registrationId")
                            val messageId = envelope.getString("messageId")
                            var result: RelayMessageDto? = null
                            for (session in auth.getSessions()) {
                                val record = registrations.read(session.sessionId) ?: continue
                                if (record.registrationId != registrationId || record.state != PushRegistrationState.ACTIVE ||
                                    record.endpoint == null || record.credentialBinding != PushRegistrationGuard.binding(session)) continue
                                counts.increment("probe_fetch_attempts")
                                result = source.fetch(record.relayIdentity, registrationId, messageId, record.managementToken)
                                break
                            }
                            result
                        }
                        else -> null
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
                if (data == null) { counts.increment("unavailable"); continue }
                val rawBytes = try {
                    data.validate(envelope!!.getString("registrationId"), envelope.getString("messageId"))
                    data.bytes().size
                } catch (_: Exception) { counts.increment("unavailable"); continue }
                counts.increment("measured")
                counts.increment(when {
                    rawBytes <= 1024 -> "raw_le_1k"
                    rawBytes <= 2048 -> "raw_k1_2"
                    rawBytes <= 3072 -> "raw_k2_3"
                    rawBytes <= 4096 -> "raw_k3_4"
                    else -> "raw_gt_4k"
                })
                val jsonBytes = JSONObject().put("version", "1").put("registrationId", data.registrationId)
                    .put("messageId", data.messageId).put("encoding", data.encoding).put("headers", data.headers)
                    .put("body", data.body).put("transport", "inline").toString().toByteArray(Charsets.UTF_8).size
                counts.increment(if (jsonBytes <= 3500) "json_inline_eligible" else "json_fetch_required")
                saveCounters("observing")
                if (counts.getValue("new_receive_work") >= 50) break
            }
            delay(1000)
        }
        assertEquals(counts.getValue("new_receive_work"), counts.getValue("measured") + counts.getValue("unavailable"))
        assertEquals(counts.getValue("measured"), counts.filterKeys { it.startsWith("raw_") }.values.sum())
        saveCounters("complete")
        instrumentation.sendStatus(0, Bundle().apply {
            putString("push_size_statistics", "new_work_only_no_payload_export")
            putString("observation_seconds", seconds.toString())
            counts.forEach { (key, value) -> putString(key, value.toString()) }
        })
    }

    private fun readEnvelopes(database: File): List<Pair<String, JSONObject?>> {
        if (!database.isFile) return emptyList()
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,input FROM workspec WHERE worker_class_name = ?",
                arrayOf("io.github.ponpokoo.mastodonclient.notification.FcmReceiveWorker")).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val data = try { Data.fromByteArray(cursor.getBlob(1)).getString("envelope")?.let(::JSONObject) }
                            catch (_: Exception) { null }
                        add(cursor.getString(0) to data)
                    }
                }
            }
        }
    }

    @Test fun aggregateRetainedReceiveWorkWithoutExportingPayloads() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pushSizeStats") == "retained")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val database = File(instrumentation.targetContext.noBackupFilesDir, "androidx.work.workdb")
        val counts = linkedMapOf(
            "retained_receive_work" to 0,
            "inline" to 0,
            "fetch_size_unknown" to 0,
            "unsupported_or_unreadable" to 0,
            "inline_le_1k" to 0,
            "inline_k1_2" to 0,
            "inline_k2_3" to 0,
            "inline_k3_4" to 0,
            "inline_gt_4k" to 0,
        )
        if (database.isFile) {
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT input FROM workspec WHERE worker_class_name = ?",
                    arrayOf("io.github.ponpokoo.mastodonclient.notification.FcmReceiveWorker"),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        counts.increment("retained_receive_work")
                        // Parse only inside the target process; errors never include the source data.
                        val envelope = try {
                            Data.fromByteArray(cursor.getBlob(0)).getString("envelope")?.let(::JSONObject)
                        } catch (_: Exception) { null }
                        when {
                            envelope == null || envelope.optString("version") != "1" -> counts.increment("unsupported_or_unreadable")
                            envelope.optString("transport") == "fetch" -> counts.increment("fetch_size_unknown")
                            envelope.optString("transport") == "inline" -> {
                                val body = envelope.optString("body")
                                val unpadded = body.trimEnd('=')
                                if (unpadded.isEmpty() || unpadded.length % 4 == 1 ||
                                    !unpadded.matches(Regex("[A-Za-z0-9_-]+")) || body.length - unpadded.length > 2) {
                                    counts.increment("unsupported_or_unreadable")
                                    continue
                                }
                                counts.increment("inline")
                                val bytes = unpadded.length.toLong() * 3 / 4
                                counts.increment(when {
                                    bytes <= 1024 -> "inline_le_1k"
                                    bytes <= 2048 -> "inline_k1_2"
                                    bytes <= 3072 -> "inline_k2_3"
                                    bytes <= 4096 -> "inline_k3_4"
                                    else -> "inline_gt_4k"
                                })
                            }
                            else -> counts.increment("unsupported_or_unreadable")
                        }
                    }
                }
            }
        }
        assertEquals(counts.getValue("retained_receive_work"),
            counts.getValue("inline") + counts.getValue("fetch_size_unknown") + counts.getValue("unsupported_or_unreadable"))
        assertEquals(counts.getValue("inline"),
            counts.filterKeys { it.startsWith("inline_") }.values.sum())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("push_size_statistics", "retained_work_snapshot_not_complete_ingress_history")
            putString("database_present", database.isFile.toString())
            counts.forEach { (key, value) -> putString(key, value.toString()) }
        })
    }

    private fun MutableMap<String, Int>.increment(key: String) { this[key] = getValue(key) + 1 }
}
