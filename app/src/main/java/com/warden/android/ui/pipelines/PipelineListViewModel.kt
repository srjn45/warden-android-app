package com.warden.android.ui.pipelines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Pipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PipelineListUiState(
    val pipelines: List<Pipeline> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** True when the daemon has no pipelines endpoint (pre-pipeline build → 404). */
    val unsupported: Boolean = false,
)

/**
 * Backs the Pipelines tab. Unlike the agents list there is no SSE stream for
 * pipelines, so this loads a REST snapshot on init, on every host switch, and on
 * pull-to-refresh. A 404 (daemon without the pipelines API) is shown as an empty
 * "not supported" state rather than an error.
 */
class PipelineListViewModel(private val repo: WardenRepository) : ViewModel() {

    private val _state = MutableStateFlow(PipelineListUiState())
    val state: StateFlow<PipelineListUiState> = _state.asStateFlow()

    init {
        // Reload (and clear) whenever the active host changes; the first emission
        // drives the initial load.
        viewModelScope.launch {
            repo.hosts
                .map { it.activeLabel }
                .distinctUntilChanged()
                .collect {
                    _state.update {
                        it.copy(pipelines = emptyList(), loading = true, error = null, unsupported = false)
                    }
                    load(isRefresh = false)
                }
        }
    }

    /** Pull-to-refresh entry point. */
    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean) {
        _state.update {
            it.copy(refreshing = isRefresh, loading = it.pipelines.isEmpty() && !isRefresh)
        }
        viewModelScope.launch {
            repo.listPipelines()
                .onSuccess { list ->
                    _state.update {
                        it.copy(
                            pipelines = list,
                            loading = false,
                            refreshing = false,
                            error = null,
                            unsupported = false,
                        )
                    }
                }
                .onFailure { e ->
                    val code = (e as? WardenRepository.HttpStatusException)?.code
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            unsupported = code == 404,
                            error = if (code == 404) null else (e.message ?: "Could not load pipelines"),
                        )
                    }
                }
        }
    }

    class Factory(private val repo: WardenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PipelineListViewModel(repo) as T
    }
}
