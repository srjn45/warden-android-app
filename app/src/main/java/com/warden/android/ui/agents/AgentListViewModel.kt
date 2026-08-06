package com.warden.android.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.SettingsStore
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Transient one-shot text for the snackbar (delete result / failure). */
    val message: String? = null,
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

    /** The live SSE collection; cancelled + replaced whenever the host switches. */
    private var streamJob: Job? = null

    init {
        observeActiveHost()
    }

    /**
     * One VM survives host switches: it watches [WardenRepository.hosts] and, each
     * time the active host changes, clears the list and (re)opens the stream
     * against the new host. This tears the old socket down deterministically —
     * cheaper and leak-free versus re-keying a fresh ViewModel per host.
     */
    private fun observeActiveHost() {
        viewModelScope.launch {
            repo.hosts
                .map { it.activeLabel }
                .distinctUntilChanged()
                .collect {
                    _state.update {
                        it.copy(
                            hostLabel = repo.active?.baseUrl ?: "",
                            agents = emptyList(),
                            stream = StreamStatus.Connecting,
                            error = null,
                        )
                    }
                    startStream()
                }
        }
    }

    /**
     * Collects the SSE snapshot stream for the active host. On failure it flips to
     * Disconnected (tmux holds the session server-side, so a dropped socket is
     * cheap to re-establish — design.md §2.1, §6); pull-to-refresh and Reconnect
     * give a manual retry.
     *
     * Agents are stored in the daemon's own order — we deliberately do NOT sort.
     * Ordering by status/updated_at made rows jump on every agent action, which
     * is disorienting on mobile; grouping (below) is the opt-in way to organise.
     */
    private fun startStream() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
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

    /**
     * Terminate + delete [session] (optionally removing its worktree). The row
     * vanishes from the list on the next SSE snapshot; here we only report the
     * outcome — a server warning or a failure — via the transient [message].
     */
    fun deleteAgent(session: Session, removeWorktree: Boolean) {
        viewModelScope.launch {
            repo.deleteAgent(session.id, removeWorktree)
                .onSuccess { warning ->
                    _state.update {
                        it.copy(message = warning ?: "Deleted ${session.displayName}")
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(message = "Delete failed: ${e.message ?: "unknown error"}")
                    }
                }
        }
    }

    /** Acknowledge the transient snackbar message. */
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Manual reconnect for the Disconnected state. */
    fun reconnect() {
        _state.update { it.copy(stream = StreamStatus.Connecting, error = null) }
        startStream()
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
