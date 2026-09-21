package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex

internal object PushRegistrationGuard {
    val mutex = Mutex()
    fun binding(session: AccountSession): String {
        val input = listOf(session.instanceUrl, session.accountId, session.accessToken).joinToString("") { "${it.length}:$it" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
    }
}
