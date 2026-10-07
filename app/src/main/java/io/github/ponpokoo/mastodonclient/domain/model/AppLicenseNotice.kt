package io.github.ponpokoo.mastodonclient.domain.model

data class AppLicenseNotice(
    val id: String,
    val title: String,
    val version: String,
    val license: String,
    val text: String,
)
