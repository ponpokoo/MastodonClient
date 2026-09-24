package io.github.ponpokoo.mastodonclient.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ponpokoo.mastodonclient.R
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

import io.github.ponpokoo.mastodonclient.domain.model.PushNotification

class SystemNotificationDataSource(private val context: android.content.Context) {
    private val delivery by lazy {
        val seenStore = context.getSharedPreferences("delivered_notifications", android.content.Context.MODE_PRIVATE)
        NotificationDeliveryCoordinator(
            read = { sessionId ->
                val stored = org.json.JSONArray(seenStore.getString(sessionId, "[]"))
                (0 until stored.length()).map { stored.getString(it) }
            },
            write = { sessionId, ids ->
                seenStore.edit().putString(sessionId, org.json.JSONArray(ids).toString()).commit()
                Unit
            },
        )
    }
    private val avatarClient by lazy {
        OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    suspend fun showNotification(
        session: AccountSession,
        notification: TimelineNotification,
        isCurrent: suspend () -> Boolean = { true },
    ): Unit = kotlinx.coroutines.withContext(Dispatchers.IO) {
        NotificationPollingScheduler.ensureNotificationChannel(context)
        val accountName = notification.account.displayName.ifBlank { notification.account.accountName }
        val title = notificationTitle(notification.type, accountName)
        val statusText = notification.status?.contentHtml.orEmpty().toPlainText()
        val body = statusText.ifBlank {
            "@${notification.account.accountName} · ${session.instanceUrl.removePrefix("https://")}"
        }
        showContent(session, notification.id, title, body, notification.account.avatarUrl) { isCurrent() }
    }

    suspend fun showPush(session: AccountSession, notification: PushNotification, isCurrent: suspend () -> Boolean) =
        showContent(session, notification.id, notification.title, notification.body, notification.icon.orEmpty(), isCurrent)

    private suspend fun showContent(session: AccountSession, id: String, title: String, body: String, avatarUrl: String,
        isCurrent: suspend () -> Boolean,
    ): Unit = kotlinx.coroutines.withContext(Dispatchers.IO) {
        NotificationPollingScheduler.ensureNotificationChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            NotificationOpenIntent.create(context, session.sessionId, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationTag = "${session.sessionId}:$id"
        fun buildNotification(largeIcon: Bitmap? = null) =
            NotificationCompat.Builder(context, NotificationPollingScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setGroup("mastodon_${session.sessionId}")
                .build()

        val posted = delivery.deliver(session.sessionId, id, isCurrent = { canPostNotifications() && isCurrent() }) {
            try {
                NotificationManagerCompat.from(context).notify(notificationTag, 0, buildNotification())
                true
            } catch (_: SecurityException) {
                // The user can revoke notification permission after the request starts.
                false
            }
        }
        if (!posted) return@withContext

        // Post promptly, then enrich the same notification without alerting twice.
        val avatar = loadAvatarIcon(avatarUrl) ?: return@withContext
        if (!canPostNotifications() || !isCurrent()) return@withContext
        val isStillVisible = try {
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any { it.tag == notificationTag && it.id == 0 }
        } catch (_: SecurityException) {
            false
        }
        if (!isStillVisible) return@withContext
        try {
            NotificationManagerCompat.from(context).notify(notificationTag, 0, buildNotification(avatar))
        } catch (_: SecurityException) {
            // The user can revoke notification permission while the avatar is loading.
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
        const val MAX_BODY_LENGTH = 240
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
        const val AVATAR_ICON_SIZE = 128
    }
}
