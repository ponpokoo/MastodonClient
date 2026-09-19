package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.repository.AppMaintenanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CacheSettingsState(val isClearing: Boolean = false, val message: String? = null)

class SettingsMaintenanceViewModel(private val repository: AppMaintenanceRepository) : ViewModel() {
    val versionName get() = repository.versionName
    val versionCode get() = repository.versionCode
    private val state = MutableStateFlow(CacheSettingsState())
    val cacheState = state.asStateFlow()

    fun clearCache() {
        if (state.value.isClearing) return
        state.value = CacheSettingsState(isClearing = true)
        viewModelScope.launch {
            val result = runCatchingCancellable { repository.clearImageCache() }
            state.value = CacheSettingsState(message = if (result.isSuccess) {
                "画像キャッシュを削除しました。"
            } else {
                "キャッシュを削除できませんでした。もう一度お試しください。"
            })
        }
    }
}
