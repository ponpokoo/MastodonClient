package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.data.local.AppLicensesLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AppLicenseNotice
import io.github.ponpokoo.mastodonclient.domain.repository.AppLicensesRepository

class DefaultAppLicensesRepository(private val local: AppLicensesLocalDataSource) : AppLicensesRepository {
    override suspend fun readNotices(): List<AppLicenseNotice> = local.readNotices().map {
        AppLicenseNotice(it.id, it.title, it.version, it.license, it.text)
    }
}
