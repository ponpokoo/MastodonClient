package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkAvailability(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    fun isAvailable(): Boolean {
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
