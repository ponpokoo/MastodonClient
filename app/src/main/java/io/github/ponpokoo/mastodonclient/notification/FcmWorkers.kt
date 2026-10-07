package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import io.github.ponpokoo.mastodonclient.BuildConfig
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.repository.PushControlStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Relay v1 addresses recipients by registration token. FID migration requires a versioned relay contract.
@Suppress("DEPRECATION")
internal suspend fun currentFcmToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { continuation.resume(it) }
        .addOnFailureListener { continuation.resumeWithException(it) }
        .addOnCanceledListener { continuation.cancel() }
}

class FcmTokenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        return runCatchingCancellable {
            val token = withTimeoutOrNull(30_000) { currentFcmToken() } ?: return retryOrFinish()
            val runtime = PushRuntime.get(applicationContext)
            runtime.onTokenChanged(token)
            if (runtime.control.states.value.values.any { it.status in setOf(PushControlStatus.ERROR, PushControlStatus.REMOVING, PushControlStatus.REGISTERING) }) retryOrFinish()
            else Result.success()
        }.getOrElse { retryOrFinish() }
    }
    private fun retryOrFinish() = if (runAttemptCount < 5) Result.retry() else Result.failure()
}

class FcmReceiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (System.currentTimeMillis() >= inputData.getLong("expires", 0)) return Result.success()
        val encoded = inputData.getString("envelope") ?: return Result.success()
        val data = runCatching { Json.decodeFromString<Map<String, String>>(encoded) }.getOrNull() ?: return Result.success()
        if (FcmEnvelope.encode(data) == null) return Result.success()
        return runCatchingCancellable {
            PushMessageHandler(applicationContext).receive(data) { System.currentTimeMillis() < inputData.getLong("expires", 0) }
            Result.success()
        }.getOrElse { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}

class PushSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString("sessionId") ?: return Result.success()
        val registrationId = inputData.getString("registrationId") ?: return Result.success()
        return runCatchingCancellable {
            val finished = withTimeoutOrNull(120_000) {
                PushRuntime.get(applicationContext).sync.sync(sessionId, registrationId)
                true
            } ?: false
            if (finished) Result.success() else if (runAttemptCount < 5) Result.retry() else Result.failure()
        }.getOrElse { if (runAttemptCount < 5) Result.retry() else Result.failure() }
        // Failure leaves the encrypted pending generation and cursor for the next Push/foreground recovery.
    }
}

class PushRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatchingCancellable {
        PushRuntime.get(applicationContext).sync.recover(null, inputData.getBoolean("force", false))
        Result.success()
    }.getOrElse { if (runAttemptCount < 5) Result.retry() else Result.failure() }
}
