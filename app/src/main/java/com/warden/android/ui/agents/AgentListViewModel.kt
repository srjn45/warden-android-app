package com.warden.android.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Connection state of the live SSE stream backing the list. */
enum class StreamStatus { Connecting, Live, Disconnected }

data class AgentListUiState(
    val agents: List<Session> = emptyList(),
    val stream: StreamStatus = StreamStatus.Connecting,
    val refreshing: Boolean = false,
    val hostLabel: String = "",
    val groupMode: GroupMode = GroupMode.None,
    val error: String? = null,
)

class AgentListViewModel(
    private val repo: WardenRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AgentListUiState(
            hostLabel = repo.active?.baseUrl ?: "",
            groupMode = GroupMode.fromId(settings.groupModeId),
        ),
    )
    val state: StateFlow<AgentListUiState> = _state.asStateFlow()

    init {
        observeStream()
    }

    /**
     * Collects the SSE snapshot stream. On failure it flips to Disconnected and
     * re-subscribes (tmux holds the session server-side, so a dropped socket is
     * cheap to re-establish — design.md §2.1, §6). No aggressive backoff needed
     * for P0; a fresh collect is triggered on the next lifecycle resume via the
     * ViewModel staying alive, and pull-to-refresh gives a manual retry.
     *
     * Agents are stored in the daemon's own order — we deliberately do NOT sort.
     * Ordering by status/updated_at made rows jump on every agent action, which
     * is disorienting on mobile; grouping (below) is the opt-in way to organise.
     */
    private fun observeStream() {
        viewModelScope.launch {
            repo.sessionStream()
                .catch { e ->
                    _state.update {
                        it.copy(stream = StreamStatus.Disconnected, error = e.message)
                    }
                }
                .collect { snapshot ->
                    _state.update {
                        it.copy(
                            agents = snapshot.sessions,
                            stream = StreamStatus.Live,
                            error = null,
                        )
                    }
                }
        }
    }

    /** Pull-to-refresh: one-shot REST snapshot, independent of the stream. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            repo.listSessions()
                .onSuccess { list ->
                    _state.update {
                        it.copy(agents = list, refreshing = false, error = null)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(refreshing = false, error = e.message) }
                }
        }
    }

    /** Change the group-by dimension and persist it (mirrors the web preference). */
    fun setGroupMode(mode: GroupMode) {
        settings.groupModeId = mode.id
        _state.update { it.copy(groupMode = mode) }
    }

    /** Manual reconnect for the Disconnected state. */
    fun reconnect() {
        _state.update { it.copy(stream = StreamStatus.Connecting, error = null) }
        observeStream()
    }

    class Factory(
        private val repo: WardenRepository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AgentListViewModel(repo, settings) as T
    }
}
