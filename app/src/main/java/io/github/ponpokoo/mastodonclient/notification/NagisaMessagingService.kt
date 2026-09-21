package io.github.ponpokoo.mastodonclient.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

class NagisaMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION") // Matches the existing Relay v1 registration-token contract.
    override fun onNewToken(token: String) {
        // Read the newest SDK token in a serialized worker, rather than persisting a stale callback token.
        enqueue { FcmWorkScheduler.syncToken(this) }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        // Relay must send data-only messages; notification payloads bypass our background handler.
        if (message.notification != null) return
        enqueue { FcmWorkScheduler.receive(this, message.data, message.sentTime, message.ttl, message.priority == RemoteMessage.PRIORITY_HIGH) }
    }
    private fun enqueue(action: () -> androidx.work.Operation?) {
        try {
            // Ensure the durable handoff finishes before Firebase releases its service callback.
            action()?.result?.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            android.util.Log.w("NagisaPush", "Unable to persist notification work")
        }
    }
}
