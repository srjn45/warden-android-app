package com.warden.android.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.SpawnOutcome
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Session
import com.warden.android.data.model.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateTerminalUiState(
    val cwd: String = "",
    val name: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    /** Non-null → the spawn gate warned; show the "open anyway?" dialog. */
    val confirm: Verdict? = null,
    /** Non-null → the working-dir browser modal is open. */
    val browser: DirBrowser? = null,
    /** Non-null → the terminal opened; the screen navigates into its PTY. */
    val created: Session? = null,
)

/**
 * Backs the "New terminal" screen. A terminal is a free-form shell pane, so the
 * form is just an optional working directory (with the shared folder browser) and
 * an optional name — no backend/model/role/prompt. Submits `POST /spawn` with
 * `kind=terminal` via [WardenRepository.createTerminal], mapping the daemon's 428
 * spawn-gate warning into a confirm dialog that re-submits.
 */
class CreateTerminalViewModel(private val repo: WardenRepository) : ViewModel() {

    private val _state = MutableStateFlow(CreateTerminalUiState())
    val state: StateFlow<CreateTerminalUiState> = _state.asStateFlow()

    fun setCwd(v: String) = _state.update { it.copy(cwd = v) }
    fun setName(v: String) = _state.update { it.copy(name = v) }

    // --- working-dir browser (shared DirBrowserSheet) --------------------

    fun openBrowser() {
        _state.update { it.copy(browser = DirBrowser(loading = true)) }
        browse(_state.value.cwd.ifBlank { null })
    }

    fun closeBrowser() = _state.update { it.copy(browser = null) }

    fun browse(path: String?) {
        _state.update { it.copy(browser = (it.browser ?: DirBrowser()).copy(loading = true, error = null)) }
        viewModelScope.launch {
            repo.listDirs(path)
                .onSuccess { listing ->
                    _state.update { it.copy(browser = DirBrowser(listing = listing, loading = false)) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(browser = (it.browser ?: DirBrowser()).copy(loading = false, error = e.message ?: "Could not list directory"))
                    }
                }
        }
    }

    fun chooseCurrentDir() {
        val chosen = _state.value.browser?.listing?.path
        _state.update { it.copy(cwd = chosen ?: it.cwd, browser = null) }
    }

    // --- submit -----------------------------------------------------------

    fun submit() = open(force = false)

    fun confirmOpen() {
        _state.update { it.copy(confirm = null) }
        open(force = true)
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    private fun open(force: Boolean) {
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val s = _state.value
            // createTerminal reuses spawn; force is applied by re-sending on confirm.
            val outcome = if (force) {
                repo.spawn(
                    com.warden.android.data.model.SpawnRequest(
                        kind = com.warden.android.data.model.Kind.TERMINAL,
                        cwd = s.cwd.trim(),
                        name = s.name.trim(),
                        force = true,
                    ),
                )
            } else {
                repo.createTerminal(s.cwd, s.name)
            }
            when (outcome) {
                is SpawnOutcome.Created ->
                    _state.update { it.copy(submitting = false, created = outcome.session) }
                is SpawnOutcome.NeedsConfirmation ->
                    _state.update { it.copy(submitting = false, confirm = outcome.verdict) }
                is SpawnOutcome.Failed ->
                    _state.update { it.copy(submitting = false, error = outcome.message) }
            }
        }
    }

    class Factory(private val repo: WardenRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CreateTerminalViewModel(repo) as T
    }
}
