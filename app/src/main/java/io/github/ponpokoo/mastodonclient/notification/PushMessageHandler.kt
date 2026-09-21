package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.remote.RelayMessageDataSource
import io.github.ponpokoo.mastodonclient.data.repository.DefaultPushMessageRepository
import io.github.ponpokoo.mastodonclient.data.repository.DefaultSystemNotificationRepository
import io.github.ponpokoo.mastodonclient.core.preferences.UserPreferencesStore
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.PushNotification
import io.github.ponpokoo.mastodonclient.domain.repository.PushNotificationPresenter
import kotlinx.coroutines.flow.first

/** Shared receive boundary used by the Firebase worker. */
class PushMessageHandler(context: Context) {
    private val appContext = context.applicationContext
    suspend fun receive(data: Map<String, String>, notExpired: () -> Boolean = { true }) = DefaultPushMessageRepository(
        sessions = SecureAuthStore(appContext)::getSessions,
        store = EncryptedPushRegistrationStore(appContext),
        source = RelayMessageDataSource(),
        presenter = object : PushNotificationPresenter {
            private val delegate = DefaultSystemNotificationRepository(SystemNotificationDataSource(appContext))
            private val preferences = UserPreferencesStore(appContext)
            private suspend fun allowed() = notExpired() && (!PushVisibility.foreground || preferences.preferences.first().foregroundNotificationsEnabled)
            override suspend fun show(session: AccountSession, notification: PushNotification, isCurrent: suspend () -> Boolean) {
                if (allowed()) delegate.show(session, notification) { isCurrent() && allowed() }
            }
        },
    ).receive(data)
}
