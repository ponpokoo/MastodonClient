package io.github.ponpokoo.mastodonclient.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object NotificationPollingScheduler {
    private const val PERIODIC_JOB_ID = 0x504f4e
    private const val IMMEDIATE_JOB_ID = 0x504f4f
    private const val INTERVAL_MILLIS = 15 * 60 * 1000L
    const val CHANNEL_ID = "mastodon_simple_notifications"

    fun ensureNotificationChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mastodonの新着通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "メンション、お気に入り、ブーストなどの新着通知"
            },
        )
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(IMMEDIATE_JOB_ID)
            return true
        }
        if (scheduler.getPendingJob(PERIODIC_JOB_ID) != null) return true
        return scheduler.schedule(
            JobInfo.Builder(
                PERIODIC_JOB_ID,
                ComponentName(context, NotificationPollingJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MILLIS)
                .setPersisted(true)
                .build(),
        ) == JobScheduler.RESULT_SUCCESS
    }

    fun runImmediately(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(IMMEDIATE_JOB_ID) != null) return true
        return scheduler.schedule(
            JobInfo.Builder(
                IMMEDIATE_JOB_ID,
                ComponentName(context, NotificationPollingJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0)
                .build(),
        ) == JobScheduler.RESULT_SUCCESS
    }
}
