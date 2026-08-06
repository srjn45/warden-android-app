package com.warden.android.ui.pipelines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Pipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PipelineDetailUiState(
    val pipeline: Pipeline? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** A lifecycle action is in flight — disable the action controls. */
    val acting: Boolean = false,
    val error: String? = null,
    /** Transient one-shot text for the snackbar (action result / failure). */
    val message: String? = null,
    /** Set once the pipeline is deleted → the screen navigates back. */
    val deleted: Boolean = false,
)

/**
 * Backs the pipeline detail screen: loads one pipeline's DAG and drives its
 * lifecycle actions (start / pause / resume / cancel / delete). Every action
 * re-reads the pipeline afterwards so the DAG + status reflect the new state;
 * a delete instead flags [PipelineDetailUiState.deleted] to pop the screen.
 */
class PipelineDetailViewModel(
    private val repo: WardenRepository,
    private val pipelineId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PipelineDetailUiState())
    val state: StateFlow<PipelineDetailUiState> = _state.asStateFlow()

    init {
        load(isRefresh = false)
    }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        _state.update { it.copy(refreshing = isRefresh, loading = it.pipeline == null && !isRefresh) }
        viewModelScope.launch {
            repo.getPipeline(pipelineId)
                .onSuccess { p ->
                    _state.update {
                        it.copy(pipeline = p, loading = false, refreshing = false, acting = false, error = null)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, acting = false, error = e.message ?: "Could not load pipeline")
                    }
                }
        }
    }

    fun start() = act("Pipeline started") { repo.startPipeline(pipelineId) }
    fun pause() = act("Pipeline paused") { repo.pausePipeline(pipelineId) }
    fun resume() = act("Pipeline resumed") { repo.resumePipeline(pipelineId) }
    fun cancel() = act("Pipeline canceled") { repo.cancelPipeline(pipelineId) }

    fun delete() {
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            repo.deletePipeline(pipelineId)
                .onSuccess { _state.update { it.copy(acting = false, deleted = true) } }
                .onFailure { e ->
                    _state.update { it.copy(acting = false, message = e.message ?: "Delete failed") }
                }
        }
    }

    /** Runs a non-destructive lifecycle action, then re-reads the pipeline. */
    private fun act(okMessage: String, call: suspend () -> Result<Unit>) {
        _state.update { it.copy(acting = true) }
        viewModelScope.launch {
            call()
                .onSuccess {
                    _state.update { it.copy(message = okMessage) }
                    load(isRefresh = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(acting = false, message = e.message ?: "Action failed") }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    class Factory(
        private val repo: WardenRepository,
        private val pipelineId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PipelineDetailViewModel(repo, pipelineId) as T
    }
}
