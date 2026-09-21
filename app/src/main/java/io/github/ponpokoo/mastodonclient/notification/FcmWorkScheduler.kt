package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

object FcmWorkScheduler {
    fun syncToken(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(
        "nagisa-fcm-token", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<FcmTokenWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
    )

    fun receive(context: Context, data: Map<String, String>, sentTime: Long, ttlSeconds: Int, highPriority: Boolean): Operation? {
        val encoded = FcmEnvelope.encode(data) ?: return null
        val expires = FcmEnvelope.deadline(sentTime, ttlSeconds, System.currentTimeMillis()) ?: return null
        val request = OneTimeWorkRequestBuilder<FcmReceiveWorker>()
            .setInputData(workDataOf("envelope" to encoded, "expires" to expires))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        if (data["transport"] == "fetch") request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // Pre-Android 12 expedited jobs require a foreground service. Use ordinary work there.
        if (highPriority && Build.VERSION.SDK_INT >= 31) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        return WorkManager.getInstance(context).enqueueUniqueWork(
            "nagisa-push-${data.getValue("registrationId")}-${data.getValue("messageId")}", ExistingWorkPolicy.KEEP, request.build(),
        )
    }
}
