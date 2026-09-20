package io.github.ponpokoo.mastodonclient.notification

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class NotificationPollingJobService : JobService() {
    private var runningScope: CoroutineScope? = null
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runningScope = scope
        runningJob = scope.launch {
            try {
                pollNotifications()
                jobFinished(params, false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                jobFinished(params, false)
            } finally {
                runningScope = null
                runningJob = null
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningScope?.cancel()
        runningJob = null
        runningScope = null
        return true
    }

    private suspend fun pollNotifications() {
        if (!io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore(applicationContext).preferences.first().simpleNotificationsEnabled) return
        NotificationPollingScheduler.ensureNotificationChannel(applicationContext)
        if (!canPostNotifications()) return
        val sessions = SecureAuthStore(applicationContext).getSessions()
        if (sessions.isEmpty()) return

        val repository = DefaultTimelineRepository(ApiClientFactory())
        val markers = getSharedPreferences(MARKERS_STORE, MODE_PRIVATE)
        sessions.forEach { session ->
            val page = try {
                repository.getNotifications(session, limit = FETCH_LIMIT).getOrThrow()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@forEach
            }
            val newestId = page.notifications.firstOrNull()?.id ?: return@forEach
            val markerKey = "last_notification_${session.sessionId}"
            val previousId = markers.getString(markerKey, null)
            if (previousId != null) {
                page.notifications
                    .takeWhile { it.id != previousId }
                    .take(MAX_NOTIFICATIONS_PER_ACCOUNT)
                    .asReversed()
                    .forEach { notifier.showNotification(session, it) }
            }
            markers.edit().putString(markerKey, newestId).apply()
        }
    }

    private val notifier by lazy { SystemNotificationDataSource(applicationContext) }
    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private companion object {
        const val MARKERS_STORE = "notification_poll_markers"
        const val FETCH_LIMIT = 40
        const val MAX_NOTIFICATIONS_PER_ACCOUNT = 5
    }
}
internal fun notificationTitle(type: String, accountName: String): String {
    val normalizedType = type.lowercase()
    if ("reaction" in normalizedType) return "$accountName さんがリアクションしました"
    return when (normalizedType) {
        "mention", "reply" -> "$accountName さんからメンション"
        "reblog" -> "$accountName さんがブーストしました"
        "favourite" -> "$accountName さんがお気に入りにしました"
        "follow" -> "$accountName さんにフォローされました"
        "follow_request" -> "$accountName さんからフォロー申請があります"
        "poll" -> "アンケートが終了しました"
        "update" -> "$accountName さんが投稿を編集しました"
        "status" -> "$accountName さんが投稿しました"
        else -> "$accountName さんから新しい通知があります"
    }
}
