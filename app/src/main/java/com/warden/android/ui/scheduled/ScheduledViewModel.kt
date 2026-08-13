package com.warden.android.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.SchedulesResult
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Schedule
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

data class ScheduledUiState(
    val schedules: List<Schedule> = emptyList(),
    /** Schedule id → the live session it spawned (present only while running). */
    val liveRuns: Map<String, Session> = emptyMap(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** True when the daemon's scheduler is configured off (403) — distinct from an error. */
    val disabled: Boolean = false,
    val error: String? = null,
    val stream: StreamStatus = StreamStatus.Connecting,
    val hostLabel: String = "",
    /** Transient one-shot text for the snackbar (toggle result / failure). */
    val message: String? = null,
)

/**
 * Backs the Scheduled screen. Two sources feed it:
 *  - the schedule definitions from `GET /schedules` (loaded on init, host switch,
 *    and pull-to-refresh — schedules are not part of the SSE stream); and
 *  - the shared fleet stream, from which it picks out the sessions a schedule
 *    spawned (by `schedule_id`) so a live run can be opened in the PTY.
 *
 * Uses the ONE shared [WardenRepository.fleet] socket — no second stream — the
 * same pattern as the Agents and Terminals screens.
 */
class ScheduledViewModel(private val repo: WardenRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        ScheduledUiState(hostLabel = repo.active?.baseUrl ?: ""),
    )
    val state: StateFlow<ScheduledUiState> = _state.asStateFlow()

    init {
        // Live runs + stream phase, projected from the shared fleet stream.
        viewModelScope.launch {
            repo.fleet.collect { fleet ->
                _state.update {
                    it.copy(
                        liveRuns = fleet.sessions
                            .filter { s -> s.isScheduled }
                            .associateBy { s -> s.scheduleId },
                        stream = fleet.phase.toStreamStatus(),
                    )
                }
            }
        }
        // Reload the schedule definitions whenever the active host changes.
        viewModelScope.launch {
            repo.hosts
                .map { it.activeLabel }
                .distinctUntilChanged()
                .collect {
                    _state.update { it.copy(hostLabel = repo.active?.baseUrl ?: "") }
                    load(refresh = false)
                }
        }
    }

    /** Load the schedule list, mapping the daemon's 403 to the disabled state. */
    private fun load(refresh: Boolean) {
        _state.update { if (refresh) it.copy(refreshing = true) else it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = repo.listSchedules()) {
                is SchedulesResult.Ok -> _state.update {
                    it.copy(
                        schedules = result.schedules,
                        loading = false,
                        refreshing = false,
                        disabled = false,
                        error = null,
                    )
                }
                SchedulesResult.Disabled -> _state.update {
                    it.copy(
                        schedules = emptyList(),
                        loading = false,
                        refreshing = false,
                        disabled = true,
                        error = null,
                    )
                }
                is SchedulesResult.Failed -> _state.update {
                    it.copy(loading = false, refreshing = false, error = result.message)
                }
            }
        }
    }

    /** Pull-to-refresh: re-fetch the schedule list. */
    fun refresh() = load(refresh = true)

    /**
     * Enable or disable [schedule]. Optimistically flips the row, then reconciles
     * with a reload; on failure it reloads to snap back to the server's truth.
     */
    fun toggleEnabled(schedule: Schedule) {
        val target = !schedule.enabled
        _state.update { s ->
            s.copy(schedules = s.schedules.map { if (it.id == schedule.id) it.copy(enabled = target) else it })
        }
        viewModelScope.launch {
            repo.setScheduleEnabled(schedule.id, target)
                .onSuccess {
                    _state.update {
                        it.copy(message = if (target) "Enabled ${schedule.displayName}" else "Disabled ${schedule.displayName}")
                    }
                    load(refresh = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(message = "Couldn't update: ${e.message ?: "unknown error"}") }
                    load(refresh = true)
                }
        }
    }

    /** Acknowledge the transient snackbar message. */
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Manual reconnect for the Disconnected state: re-opens the shared stream. */
    fun reconnect() {
        _state.update { it.copy(stream = StreamStatus.Connecting) }
        repo.reconnect()
        load(refresh = true)
    }

    class Factory(private val repo: WardenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScheduledViewModel(repo) as T
    }
}
