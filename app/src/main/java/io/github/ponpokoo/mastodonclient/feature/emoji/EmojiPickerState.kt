package io.github.ponpokoo.mastodonclient.feature.emoji

import io.github.ponpokoo.mastodonclient.domain.model.CustomEmoji
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class EmojiPickerEmojis(
    val items: List<CustomEmoji> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Operations are bound to one session by the owning ViewModel. */
internal class EmojiPickerDataSource(
    val customEmojis: Flow<EmojiPickerEmojis>,
    val loadHistory: suspend () -> List<String> = { emptyList() },
    val saveHistory: (suspend (List<String>) -> Unit)? = null,
    val loadFavorites: suspend () -> List<String> = { emptyList() },
    val updateFavorite: (suspend (String, Boolean) -> List<String>)? = null,
)

/** One open picker. Its child job is cancelled on dismissal or session invalidation. */
internal class EmojiPickerState(
    parentScope: CoroutineScope,
    private val source: EmojiPickerDataSource,
    private val checkSession: () -> Unit = {},
    private val sessionValid: Flow<Boolean> = flowOf(true),
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(EmojiPickerDataState(
        favoritesLoaded = false, historyLoaded = false,
        canEditFavorites = source.updateFavorite != null,
    ))
    val state = mutableState.asStateFlow()
    val actions = EmojiPickerActions(::retryFavorites, ::setFavorite, ::removeHistory, ::retryHistory)
    private var started = false
    private var favoritesJob: Job? = null
    private var historyJob: Job? = null
    private var historyRevision = 0L

    fun start() {
        if (started || !job.isActive) return
        started = true
        scope.launch { sessionValid.collect { if (!it) close() } }
        scope.launch {
            try {
                ensureSession()
                source.customEmojis.collect { emojis ->
                    ensureSession()
                    mutableState.update { it.copy(customEmojis = emojis.items,
                        loading = emojis.loading, loadError = emojis.error) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureSession()
                mutableState.update { it.copy(loading = false, loadError = "カスタム絵文字を取得できませんでした") }
            }
        }
        retryHistory()
        retryFavorites()
    }

    fun close() { job.cancel() }

    private suspend fun ensureSession() {
        currentCoroutineContext().ensureActive()
        job.ensureActive()
        checkSession()
    }

    fun retryFavorites() {
        if (!job.isActive || state.value.favoriteSaving) return
        favoritesJob?.cancel()
        favoritesJob = scope.launch {
            ensureSession()
            mutableState.update { it.copy(favoritesLoaded = false, favoritesError = null) }
            try {
                val favorites = source.loadFavorites().distinct()
                ensureSession()
                mutableState.update { it.copy(favorites = favorites, favoritesLoaded = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureSession()
                mutableState.update { it.copy(favoritesError = "お気に入りを読み込めませんでした") }
            }
        }
    }

    fun retryHistory() {
        if (!job.isActive || state.value.historySaving) return
        historyJob?.cancel()
        historyJob = scope.launch {
            ensureSession()
            mutableState.update { it.copy(historyLoaded = false, historyError = null) }
            try {
                val history = source.loadHistory()
                ensureSession()
                historyRevision++
                mutableState.update { it.copy(history = history, historyLoaded = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureSession()
                mutableState.update { it.copy(historyError = "履歴を読み込めませんでした") }
            }
        }
    }

    private suspend fun setFavorite(name: String, favorite: Boolean): Boolean = scope.async {
        ensureSession()
        val update = source.updateFavorite ?: return@async false
        if (!state.value.favoritesLoaded || state.value.favoriteSaving) return@async false
        mutableState.update { it.copy(favoriteSaving = true) }
        try {
            val saved = update(name, favorite)
            ensureSession()
            check((name in saved) == favorite)
            mutableState.update { it.copy(favorites = saved, favoritesError = null) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ensureSession()
            mutableState.update { it.copy(favoritesError = "お気に入りを保存できませんでした") }
            false
        } finally {
            if (job.isActive) mutableState.update { it.copy(favoriteSaving = false) }
        }
    }.await()

    private suspend fun removeHistory(name: String): EmojiPickerUndo? = scope.async {
        ensureSession()
        val previous = state.value.history
        if (name !in previous || !saveHistory(previous - name)) return@async null
        val revision = historyRevision
        EmojiPickerUndo { scope.async {
            ensureSession()
            // An old snackbar must not overwrite a more recent edit or reload.
            if (revision == historyRevision) saveHistory(previous)
            Unit
        }.await() }
    }.await()

    private suspend fun saveHistory(history: List<String>): Boolean {
        val save = source.saveHistory ?: return false
        if (!state.value.historyLoaded || state.value.historySaving) return false
        mutableState.update { it.copy(historySaving = true) }
        return try {
            save(history)
            ensureSession()
            historyRevision++
            mutableState.update { it.copy(history = history, historyError = null) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ensureSession()
            mutableState.update { it.copy(historyError = "履歴を保存できませんでした") }
            false
        } finally {
            if (job.isActive) mutableState.update { it.copy(historySaving = false) }
        }
    }
}
