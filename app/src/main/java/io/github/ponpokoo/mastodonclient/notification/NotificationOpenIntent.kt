package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ponpokoo.mastodonclient.MainActivity

data class NotificationOpenRequest(val sessionId: String, val notificationId: String)

object NotificationOpenIntent {
    private const val ACTION_OPEN = "io.github.ponpokoo.mastodonclient.OPEN_NOTIFICATION"
    private const val EXTRA_SESSION_ID = "notification_session_id"
    private const val EXTRA_NOTIFICATION_ID = "notification_id"
    private const val LEGACY_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun create(context: Context, sessionId: String, notificationId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            // PendingIntent identity ignores extras. Keep each account/notification tap distinct.
            data = Uri.Builder().scheme("mastodonclient-notification").authority("open")
                .appendPath(sessionId).appendPath(notificationId).build()
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    fun parse(intent: Intent?): NotificationOpenRequest? {
        // Notifications posted by older builds launched MainActivity without an action or extras.
        if (intent != null && intent.action == null && intent.component?.className == MainActivity::class.java.name &&
            intent.data == null && (intent.flags and LEGACY_FLAGS) == LEGACY_FLAGS
        ) return NotificationOpenRequest("", "")
        if (intent?.action != ACTION_OPEN) return null
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return null
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)?.takeIf(String::isNotBlank) ?: return null
        return NotificationOpenRequest(sessionId, notificationId)
    }

    fun clear(intent: Intent?) {
        if (intent != null && parse(intent) == NotificationOpenRequest("", "")) {
            intent.flags = intent.flags and LEGACY_FLAGS.inv()
            return
        }
        if (intent?.action != ACTION_OPEN) return
        intent.action = null
        intent.data = null
        intent.flags = intent.flags and LEGACY_FLAGS.inv()
        intent.removeExtra(EXTRA_SESSION_ID)
        intent.removeExtra(EXTRA_NOTIFICATION_ID)
    }
}
