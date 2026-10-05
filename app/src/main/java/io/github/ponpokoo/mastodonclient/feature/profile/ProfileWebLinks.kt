package io.github.ponpokoo.mastodonclient.feature.profile

import java.net.URI

internal data class ProfileWebLinks(
    val profile: String,
    val settings: String?,
    val about: String?,
)

// Settings belong to the browsing session; About belongs to the displayed profile.
internal fun profileWebLinks(profileUrl: String, instanceUrl: String?): ProfileWebLinks {
    val server = instanceUrl?.trimEnd('/')?.takeIf(String::isNotBlank)
    val about = runCatching {
        val profile = URI(profileUrl)
        if (profile.scheme?.lowercase() in setOf("http", "https") &&
            !profile.host.isNullOrBlank() && profile.userInfo == null) {
            URI(profile.scheme, null, profile.host, profile.port, "/about", null, null).toASCIIString()
        } else null
    }.getOrNull()
    return ProfileWebLinks(
        profile = profileUrl,
        settings = server?.let { "$it/settings/profile" },
        about = about,
    )
}
