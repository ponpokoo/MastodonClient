package io.github.ponpokoo.mastodonclient.notification

import android.Manifest
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ponpokoo.mastodonclient.MainActivity
import io.github.ponpokoo.mastodonclient.R
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.repository.DefaultTimelineRepository
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

class NotificationPollingJobService : JobService() {
    private var runningScope: CoroutineScope? = null
    private var runningJob: Job? = null
    private val avatarClient by lazy {
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
    }

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
                    .forEach { showNotification(session, it) }
            }
            markers.edit().putString(markerKey, newestId).apply()
        }
    }

    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun showNotification(session: AccountSession, notification: TimelineNotification) {
        val accountName = notification.account.displayName.ifBlank { notification.account.accountName }
        val title = notificationTitle(notification.type, accountName)
        val statusText = notification.status?.contentHtml.orEmpty().toPlainText()
        val body = statusText.ifBlank {
            "@${notification.account.accountName} · ${session.instanceUrl.removePrefix("https://")}"
        }
        val avatar = loadAvatarIcon(notification.account.avatarUrl)
        val requestCode = "${session.sessionId}:${notification.id}".hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val systemNotification = NotificationCompat.Builder(this, NotificationPollingScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setGroup("mastodon_${session.sessionId}")
            .build()

        try {
            NotificationManagerCompat.from(this).notify(requestCode, systemNotification)
        } catch (_: SecurityException) {
            // The user can revoke notification permission after the polling job starts.
        }
    }

    private fun loadAvatarIcon(url: String): Bitmap? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        return runCatching {
            avatarClient.newCall(Request.Builder().url(url).get().build()).execute().use responseUse@ { response ->
                if (!response.isSuccessful) return@responseUse null
                val responseBody = response.body ?: return@responseUse null
                if (responseBody.contentLength() > MAX_AVATAR_BYTES) return@responseUse null
                val bytes = responseBody.byteStream().use inputUse@ { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_AVATAR_BYTES) return@inputUse null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: return@responseUse null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@responseUse null
                if (decoded.width <= AVATAR_ICON_SIZE && decoded.height <= AVATAR_ICON_SIZE) decoded
                else Bitmap.createScaledBitmap(decoded, AVATAR_ICON_SIZE, AVATAR_ICON_SIZE, true)
            }
        }.getOrNull()
    }

    private fun String.toPlainText(): String = Html.fromHtml(
        this,
        Html.FROM_HTML_MODE_COMPACT,
    ).toString().trim().take(MAX_BODY_LENGTH)

    private companion object {
        const val MARKERS_STORE = "notification_poll_markers"
        const val FETCH_LIMIT = 40
        const val MAX_NOTIFICATIONS_PER_ACCOUNT = 5
        const val MAX_BODY_LENGTH = 240
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
        const val AVATAR_ICON_SIZE = 128
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
