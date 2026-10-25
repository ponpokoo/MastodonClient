package io.github.ponpokoo.mastodonclient.domain.repository

/** Explicit image revalidation, independent of account metadata reads and synchronization. */
fun interface AccountAvatarRepository {
    suspend fun revalidate(url: String): Boolean
}
