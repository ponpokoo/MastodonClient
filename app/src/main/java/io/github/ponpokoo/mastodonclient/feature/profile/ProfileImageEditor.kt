package io.github.ponpokoo.mastodonclient.feature.profile

import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfileImageSlot { Avatar, Header }

data class ProfileImageEditState(
    val isOpen: Boolean = false,
    val avatarPath: String? = null,
    val headerPath: String? = null,
    val importing: Set<ProfileImageSlot> = emptySet(),
    val isSaving: Boolean = false,
    val error: String? = null,
)

/** Owns imports and editable files; a save lease owns its captured files until completion. */
internal class ProfileImageEditor(
    private val repository: ProfileImageRepository?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ProfileImageEditState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<ProfileImageSlot, Job>()
    private val generations = mutableMapOf<ProfileImageSlot, Long>()
    private var editGeneration = 0L
    private val savingPaths = mutableSetOf<String>()

    fun open() { if (!state.value.isSaving) mutableState.update { it.copy(isOpen = true, error = null) } }

    fun dismiss() {
        editGeneration++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        delete(editablePaths().filterNot { it in savingPaths })
        mutableState.value = ProfileImageEditState(isSaving = state.value.isSaving)
    }

    fun select(slot: ProfileImageSlot, uri: String) {
        val source = repository ?: return
        if (!state.value.isOpen || state.value.isSaving) return
        jobs[slot]?.cancel()
        val generation = (generations[slot] ?: 0) + 1
        generations[slot] = generation
        val edit = editGeneration
        mutableState.update { it.copy(importing = it.importing + slot, error = null) }
        jobs[slot] = scope.launch {
            var imported: String? = null
            var adopted = false
            try {
                imported = source.importImage(uri)
                currentCoroutineContext().ensureActive()
                if (edit != editGeneration || generation != generations[slot] || !state.value.isOpen) return@launch
                val old = if (slot == ProfileImageSlot.Avatar) state.value.avatarPath else state.value.headerPath
                mutableState.update { if (slot == ProfileImageSlot.Avatar) it.copy(avatarPath = imported)
                    else it.copy(headerPath = imported) }
                adopted = true
                old?.let { delete(listOf(it)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (edit == editGeneration && generation == generations[slot]) {
                    mutableState.update { it.copy(error = "画像を取り込めませんでした。選択し直してください") }
                }
            } finally {
                if (!adopted && imported != null) withContext(NonCancellable) { source.deleteImages(listOf(imported)) }
                if (edit == editGeneration && generation == generations[slot]) {
                    mutableState.update { it.copy(importing = it.importing - slot) }
                }
            }
        }
    }

    data class Save(val request: ProfileEditRequest, val paths: List<String>, val generation: Long)

    fun beginSave(request: ProfileEditRequest): Save? {
        if (state.value.isSaving || state.value.importing.isNotEmpty()) return null
        val paths = editablePaths()
        savingPaths.addAll(paths)
        val captured = request.copy(avatarFilePath = state.value.avatarPath ?: request.avatarFilePath,
            headerFilePath = state.value.headerPath ?: request.headerFilePath)
        mutableState.update { it.copy(isSaving = true, error = null) }
        return Save(captured, paths, editGeneration)
    }

    suspend fun finishSave(save: Save, success: Boolean) {
        savingPaths.removeAll(save.paths.toSet())
        if (success || save.generation != editGeneration || !state.value.isOpen) {
            withContext(NonCancellable) { repository?.deleteImages(save.paths) }
        }
        if (save.generation == editGeneration) {
            if (success) mutableState.value = ProfileImageEditState()
            else mutableState.update { it.copy(isSaving = false, error = "プロフィールを更新できませんでした。再試行できます") }
        } else mutableState.update { it.copy(isSaving = savingPaths.isNotEmpty()) }
    }

    private fun editablePaths() = listOfNotNull(state.value.avatarPath, state.value.headerPath)
    private fun delete(paths: List<String>) {
        if (paths.isNotEmpty()) scope.launch(NonCancellable) { repository?.deleteImages(paths) }
    }
}
