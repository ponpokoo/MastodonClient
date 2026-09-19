package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.local.AppMaintenanceDataSource
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository

class DefaultAppMaintenanceRepository(private val local: AppMaintenanceDataSource) : AppMaintenanceRepository {
    override val versionName get() = local.versionName
    override val versionCode get() = local.versionCode
    override suspend fun clearImageCache() = local.clearImageCache()
}
