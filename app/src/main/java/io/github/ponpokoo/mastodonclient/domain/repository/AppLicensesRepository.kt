package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.AppLicenseNotice

interface AppLicensesRepository {
    suspend fun readNotices(): List<AppLicenseNotice>
}
