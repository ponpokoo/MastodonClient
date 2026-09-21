package io.github.ponpokoo.mastodonclient.notification

import android.content.Context
import io.github.ponpokoo.mastodonclient.BuildConfig
import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore
import io.github.ponpokoo.mastodonclient.data.local.*
import io.github.ponpokoo.mastodonclient.data.remote.DefaultPushRelayDataSource
import io.github.ponpokoo.mastodonclient.data.repository.*

/** Application-wide wiring shared by UI and Firebase workers. */
class PushRuntime private constructor(context: Context) {
    @Volatile private var relayUrl: String? = BuildConfig.RELAY_URL.takeIf { BuildConfig.FIREBASE_CONFIGURED && it.isNotBlank() }
    private val auth = SecureAuthStore(context)
    private val registrations = EncryptedPushRegistrationStore(context)
    val control = DefaultPushControlRepository(
        sessions = auth::getSessions,
        control = EncryptedPushControlStore(context),
        registrations = registrations,
        configured = { relayUrl != null },
        repository = { saved ->
            DefaultPushRegistrationRepository(registrations,
                DefaultPushRelayDataSource(saved?.relayIdentity ?: checkNotNull(relayUrl)),
                DefaultPushSubscriptionRepository(ApiClientFactory()))
        },
    )
    /** An explicit transport override for integration environments. */
    suspend fun configureTransport(relay: String, token: String) {
        DefaultPushRelayDataSource(relay) // Validate HTTPS configuration without sending requests.
        relayUrl = relay
        control.tokenChanged(token)
    }
    suspend fun onTokenChanged(token: String?) = control.tokenChanged(token)
    companion object {
        @Volatile private var instance: PushRuntime? = null
        fun get(context: Context): PushRuntime = instance ?: synchronized(this) {
            instance ?: PushRuntime(context.applicationContext).also { instance = it }
        }
    }
}
