package io.github.ponpokoo.mastodonclient.domain.repository

interface AppMaintenanceRepository {
    val versionName: String
    val versionCode: Long
    suspend fun clearImageCache()
}
