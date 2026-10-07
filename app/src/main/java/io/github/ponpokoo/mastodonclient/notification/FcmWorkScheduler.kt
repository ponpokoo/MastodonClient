package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import android.os.Build
import io.github.ponpokoo.mastodonclient.BuildConfig
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FcmWorkScheduler {
    suspend fun syncAccount(context: Context, sessionId: String, registrationId: String) {
        val operation = WorkManager.getInstance(context).enqueueUniqueWork(
            "nagisa-push-sync-$sessionId", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PushSyncWorker>()
                .setInputData(workDataOf("sessionId" to sessionId, "registrationId" to registrationId))
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
        )
        // Do not report a successful receive until the account work is durably enqueued.
        await(operation)
    }

    suspend fun cancelSync(context: Context, sessionId: String) {
        await(WorkManager.getInstance(context).cancelUniqueWork("nagisa-push-sync-$sessionId"))
    }

    private suspend fun await(operation: Operation) {
        suspendCancellableCoroutine<Unit> { continuation ->
            operation.result.addListener({
                try { operation.result.get(); continuation.resume(Unit) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }, java.util.concurrent.Executor { it.run() })
        }
    }

    fun recover(context: Context, force: Boolean): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        "nagisa-push-recovery", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<PushRecoveryWorker>().setInputData(workDataOf("force" to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
    )
    fun syncToken(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        "nagisa-fcm-token", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
    )

    fun receive(context: Context, data: Map<String, String>, sentTime: Long, ttlSeconds: Int, highPriority: Boolean): Operation? {
        val encoded = FcmEnvelope.encode(data) ?: return null
        val expires = FcmEnvelope.deadline(sentTime, ttlSeconds, System.currentTimeMillis()) ?: return null
        val input = Data.Builder().putString("envelope", encoded).putLong("expires", expires)
        // Local opt-in instrumentation can correlate Firebase and OS timestamps in Debug builds.
        // Release keeps the delivery input unchanged; no payload or identifiers are exported.
        if (BuildConfig.DEBUG) input.putLong("measurementSentAt", sentTime)
            .putLong("measurementReceivedAt", System.currentTimeMillis())
        val request = OneTimeWorkRequestBuilder<FcmReceiveWorker>()
            .setInputData(input.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        if (data["transport"] == "fetch") request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // Pre-Android 12 expedited jobs require a foreground service. Use ordinary work there.
        if (highPriority && Build.VERSION.SDK_INT >= 31) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return WorkManager.getInstance(context).enqueueUniqueWork(
            "nagisa-push-${data.getValue("registrationId")}-${data.getValue("messageId")}", ExistingWorkPolicy.KEEP, request.build(),
        )
    }
}
