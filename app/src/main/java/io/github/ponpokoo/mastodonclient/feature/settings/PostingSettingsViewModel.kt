package io.github.ponpokoo.mastodonclient.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.core.preferences.PostVisibility
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.ServerAccountSettingsRepository
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import io.github.ponpokoo.mastodonclient.feature.common.SessionScopedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PostingSettingsState(
    val account: AccountSession? = null,
    val visibility: PostVisibility? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

class PostingSettingsViewModel(
    private val repository: ServerAccountSettingsRepository,
    browsing: BrowsingSession,
) : SessionScopedViewModel(browsing) {
    private val mutable = MutableStateFlow(PostingSettingsState())
    val state = mutable.asStateFlow()

    init {
        observeSession()
        viewModelScope.launch { repository.serverSettings.collect { presentVisibility() } }
    }

    override fun onSessionChanged(snapshot: BrowsingSession.Snapshot) {
        mutable.value = PostingSettingsState(account = snapshot.account)
        presentVisibility()
    }

    private fun presentVisibility() {
        val account = state.value.account ?: return
        val value = repository.serverSettings.value[account.sessionId]?.takeIf { it.belongsTo(account) }?.defaults?.visibility
        mutable.update { it.copy(visibility = PostVisibility.entries.firstOrNull { option -> option.apiValue == value }) }
    }

    fun setVisibility(visibility: PostVisibility) {
        val snapshot = currentSnapshot() ?: return
        val account = snapshot.account ?: return
        if (state.value.saving || visibility == state.value.visibility || visibility == PostVisibility.Direct) return
        mutable.update { it.copy(saving = true, error = null) }
        requestScope.launch {
            try {
                repository.savePostingVisibility(account, visibility.apiValue).forSession(snapshot)
                    .onSuccess {
                        presentVisibility()
                        mutable.update { it.copy(error = repository.serverSettings.value[account.sessionId]?.fetch?.get(io.github.ponpokoo.mastodonclient.domain.model.ServerSetting.Preferences)?.error) }
                    }
                    .onFailure { error -> mutable.update { it.copy(error = if (error is IllegalArgumentException) error.message else "公開範囲の保存を確認できませんでした。再度選択してやり直してください") } }
            } finally {
                if (currentSnapshot() == snapshot) mutable.update { it.copy(saving = false) }
            }
        }
    }
}
