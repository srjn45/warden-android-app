package com.warden.android.ui.terminals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session
import com.warden.android.ui.agents.StreamStatus
import com.warden.android.ui.agents.toStreamStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalsUiState(
    val terminals: List<Session> = emptyList(),
    val stream: StreamStatus = StreamStatus.Connecting,
    val refreshing: Boolean = false,
    val hostLabel: String = "",
    /** Transient one-shot text for the snackbar (delete result / failure). */
    val message: String? = null,
)

/**
 * Backs the Terminals screen. Projects the TERMINAL sessions out of the
 * repository's single shared fleet stream (the same socket the Agents screen
 * uses — no second stream), and offers open/delete plus create-terminal.
 */
class TerminalsViewModel(private val repo: WardenRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        TerminalsUiState(hostLabel = repo.active?.baseUrl ?: ""),
    )
    val state: StateFlow<TerminalsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.fleet.collect { fleet ->
                _state.update {
                    it.copy(
                        terminals = fleet.sessions.filter { s -> s.isTerminal },
                        stream = fleet.phase.toStreamStatus(),
                    )
                }
            }
        }
        viewModelScope.launch {
            repo.hosts
                .map { it.activeLabel }
                .distinctUntilChanged()
                .collect {
                    _state.update { it.copy(hostLabel = repo.active?.baseUrl ?: "") }
                }
        }
    }

    /** Pull-to-refresh: one-shot REST snapshot, filtered to terminals. */
    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            repo.listSessions()
                .onSuccess { list ->
                    _state.update {
                        it.copy(terminals = list.filter { s -> s.isTerminal }, refreshing = false)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(refreshing = false, message = e.message) }
                }
        }
    }

    /**
     * Terminate + delete a terminal. Terminals have no git worktree, so the
     * worktree-removal step is skipped. The row vanishes on the next snapshot;
     * here we only report the outcome via the transient [TerminalsUiState.message].
     */
    fun deleteTerminal(session: Session) {
        viewModelScope.launch {
            repo.deleteAgent(session.id, removeWorktree = false)
                .onSuccess { warning ->
                    _state.update {
                        it.copy(message = warning ?: "Closed ${session.displayName}")
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(message = "Close failed: ${e.message ?: "unknown error"}")
                    }
                }
        }
    }

    /** Acknowledge the transient snackbar message. */
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Manual reconnect for the Disconnected state: re-opens the shared stream. */
    fun reconnect() {
        _state.update { it.copy(stream = StreamStatus.Connecting) }
        repo.reconnect()
    }

    class Factory(private val repo: WardenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TerminalsViewModel(repo) as T
    }
}
