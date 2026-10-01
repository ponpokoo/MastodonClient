package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.network.ApiClientFactory
import io.github.ponpokoo.mastodonclient.core.security.AuthStore
import io.github.ponpokoo.mastodonclient.core.security.PendingOAuth
import io.github.ponpokoo.mastodonclient.core.security.RegisteredApplication
import io.github.ponpokoo.mastodonclient.data.local.NotificationLocalDataSource
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.CachedNotifications
import io.github.ponpokoo.mastodonclient.domain.model.TimelineNotification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationCacheLogoutTest {
    @Test fun logoutRemovesSessionBeforeDeletingCacheAndDoesNotUndoLogoutOnDiskFailure() = runTest {
        for (diskFailure in listOf(false, true)) {
            val account = AccountSession("one", "https://one.example", "me", "me", "Me", "", "unused")
            val store = MemoryAuth(account)
            var deleted: String? = null
            val cache = object : NotificationLocalDataSource {
                override suspend fun read(session: AccountSession) = CachedNotifications()
                override suspend fun write(session: AccountSession, notifications: List<TimelineNotification>) = Unit
                override suspend fun writeMarker(session: AccountSession, lastReadId: String?) = Unit
                override suspend fun deleteAccount(sessionId: String) {
                    assertNull(store.getSession())
                    deleted = sessionId
                    if (diskFailure) error("disk unavailable")
                }
            }
            val repository = DefaultAuthRepository(ApiClientFactory(), store, notificationLocalDataSource = cache)
            repository.logout()
            assertEquals("one", deleted)
            assertNull(repository.restoreSession())
        }
    }

    private class MemoryAuth(private var account: AccountSession?) : AuthStore {
        override suspend fun findApplication(instanceUrl: String): RegisteredApplication? = null
        override suspend fun saveApplication(application: RegisteredApplication) = Unit
        override suspend fun savePending(pending: PendingOAuth) = Unit
        override suspend fun getPending(): PendingOAuth? = null
        override suspend fun clearPending() = Unit
        override suspend fun saveSession(session: AccountSession) { account = session }
        override suspend fun getSessions() = listOfNotNull(account)
        override suspend fun getSession() = account
        override suspend fun setActiveSession(sessionId: String) = account?.takeIf { it.sessionId == sessionId }
        override suspend fun removeSession(sessionId: String) { if (account?.sessionId == sessionId) account = null }
    }
}
