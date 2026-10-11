package io.github.ponpokoo.mastodonclient.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ponpokoo.mastodonclient.domain.session.BrowsingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Cancels tab work on account changes, while keeping the session observers alive. */
abstract class SessionScopedViewModel(protected val browsing: BrowsingSession) : ViewModel() {
    private val requestJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    protected val requestScope = CoroutineScope(viewModelScope.coroutineContext + requestJob)
    private var boundSnapshot: BrowsingSession.Snapshot? = null

    // Called from each subclass's init after its state has been initialized.
    protected fun observeSession() {
        viewModelScope.launch {
            browsing.snapshot.collect { snapshot ->
                requestJob.cancelChildren()
                boundSnapshot = snapshot
                onSessionChanged(snapshot)
            }
        }
        viewModelScope.launch {
            browsing.events.collect { event ->
                if (event.snapshot == currentSnapshot()) onChange(event.change)
            }
        }
    }

    protected fun currentSnapshot(): BrowsingSession.Snapshot? =
        boundSnapshot?.takeIf { it.account != null && it == browsing.snapshot.value }

    protected suspend fun <T> Result<T>.forSession(snapshot: BrowsingSession.Snapshot): Result<T> {
        currentCoroutineContext().ensureActive()
        if (snapshot != currentSnapshot()) throw CancellationException("Browsing session changed")
        return this
    }

    protected abstract fun onSessionChanged(snapshot: BrowsingSession.Snapshot)
    protected open fun onChange(change: BrowsingSession.Change) = Unit
}

class ScreenViewModelFactory<T : ViewModel>(private val createViewModel: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = createViewModel() as V
}
