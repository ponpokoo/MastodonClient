package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class NetworkAvailability(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    fun observeChanges() = callbackFlow<Unit> {
        val connectivity = manager ?: run { close(); return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        trySend(Unit)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate()
    fun isAvailable(): Boolean {
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
