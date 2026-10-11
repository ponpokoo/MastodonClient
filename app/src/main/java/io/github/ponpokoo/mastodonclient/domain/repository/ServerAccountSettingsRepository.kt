package io.github.ponpokoo.mastodonclient.domain.repository

import io.github.ponpokoo.mastodonclient.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface ServerAccountSettingsRepository {
    suspend fun savePostingVisibility(session: AccountSession, visibility: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    val serverSettings: StateFlow<Map<String, ServerAccountSettings>> get() = EmptyServerSettings.state
    suspend fun prepareServerSettings(session: AccountSession) = Unit
    suspend fun activateServerSettings(session: AccountSession) = Unit
    suspend fun syncServerSettings(session: AccountSession, items: Set<ServerSetting> = ServerSetting.entries.toSet()): Result<Unit> = Result.success(Unit)
    suspend fun saveServerFilter(session: AccountSession, original: ServerFilter?, edit: FilterEdit): Result<ServerFilter> = Result.failure(UnsupportedOperationException())
    suspend fun deleteServerFilter(session: AccountSession, id: String): Result<Unit> = Result.failure(UnsupportedOperationException())
    suspend fun updateFilterDisplay(session: AccountSession, screen: String, expectedFilters: List<ServerFilter>, success: Boolean?) = Unit
}
private object EmptyServerSettings { val state = MutableStateFlow<Map<String, ServerAccountSettings>>(emptyMap()) }
