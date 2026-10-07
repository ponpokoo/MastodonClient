package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.common.runCatchingCancellable
import io.github.ponpokoo.mastodonclient.domain.model.AppLicenseNotice
import io.github.ponpokoo.mastodonclient.domain.repository.AppLicensesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LicensesState(
    val loading: Boolean = false,
    val notices: List<AppLicenseNotice> = emptyList(),
    val failed: Boolean = false,
)

class LicensesViewModel(private val repository: AppLicensesRepository) : ViewModel() {
    private val state = MutableStateFlow(LicensesState())
    val uiState = state.asStateFlow()

    fun load() {
        if (state.value.loading || state.value.notices.isNotEmpty()) return
        state.value = LicensesState(loading = true)
        viewModelScope.launch {
            val result = runCatchingCancellable { repository.readNotices() }
            state.value = result.fold(
                onSuccess = { LicensesState(notices = it) },
                onFailure = { LicensesState(failed = true) },
            )
        }
    }
}
