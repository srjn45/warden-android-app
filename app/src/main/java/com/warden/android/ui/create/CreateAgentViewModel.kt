package com.warden.android.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.SpawnOutcome
import com.warden.android.data.WardenRepository
import com.warden.android.data.model.Backend
import com.warden.android.data.model.BackendInfo
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.RoleInfo
import com.warden.android.data.model.Session
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the working-directory browser modal (backed by `GET /fs/dirs`). */
data class DirBrowser(
    val listing: DirListing? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class CreateAgentUiState(
    val backend: Backend = Backend.DEFAULT,
    /**
     * Backend picker options. Seeds from the static list so the picker is never
     * empty, then swaps in the live `GET /backends` result (which also carries
     * per-backend install [BackendInfo.available]).
     */
    val backends: List<BackendInfo> = Backend.staticInfos(),
    val cwd: String = "",
    val name: String = "",
    val role: String = "",
    val model: String = "",
    val prompt: String = "",
    val roles: List<RoleInfo> = emptyList(),
    val submitting: Boolean = false,
    val error: String? = null,
    /** Non-null → the spawn gate warned; show the "spawn anyway?" dialog. */
    val confirm: Verdict? = null,
    /** Non-null → the browser modal is open. */
    val browser: DirBrowser? = null,
    /** Non-null → spawn succeeded; the screen navigates back. */
    val created: Session? = null,
)

/**
 * Backs the create-agent sheet. Loads the role picker up front, drives the
 * working-dir browser off `GET /fs/dirs`, and submits `POST /spawn` — mapping
 * the daemon's 428 spawn-gate warning into a confirm dialog that re-submits with
 * `force = true`.
 */
class CreateAgentViewModel(private val repo: WardenRepository) : ViewModel() {

    private val _state = MutableStateFlow(CreateAgentUiState())
    val state: StateFlow<CreateAgentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.listRoles().onSuccess { roles ->
                _state.update { it.copy(roles = roles) }
            }
            // A failed roles fetch is non-fatal — the picker just stays empty.
        }
        viewModelScope.launch {
            // Prefer the live registry; keep the static seed on 404 (older
            // daemon) / network error, and ignore an empty response the same way.
            repo.listBackends().onSuccess { backends ->
                if (backends.isNotEmpty()) _state.update { it.copy(backends = backends) }
            }
        }
    }

    fun setBackend(b: Backend) = _state.update { it.copy(backend = b) }
    fun setCwd(v: String) = _state.update { it.copy(cwd = v) }
    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setRole(v: String) = _state.update { it.copy(role = v) }
    fun setModel(v: String) = _state.update { it.copy(model = v) }
    fun setPrompt(v: String) = _state.update { it.copy(prompt = v) }
    fun clearError() = _state.update { it.copy(error = null) }

    // --- working-dir browser ---------------------------------------------

    fun openBrowser() {
        _state.update { it.copy(browser = DirBrowser(loading = true)) }
        // Start from the current cwd if set, else the daemon's home default.
        browse(_state.value.cwd.ifBlank { null })
    }

    fun closeBrowser() = _state.update { it.copy(browser = null) }

    /** Navigate the browser to [path] (null = home). */
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

    /** Pick the browser's current directory as the cwd and close it. */
    fun chooseCurrentDir() {
        val chosen = _state.value.browser?.listing?.path
        _state.update {
            it.copy(cwd = chosen ?: it.cwd, browser = null)
        }
    }

    // --- submit -----------------------------------------------------------

    private fun buildRequest(force: Boolean): SpawnRequest {
        val s = _state.value
        return SpawnRequest(
            name = s.name.trim(),
            prompt = s.prompt.trim(),
            cwd = s.cwd.trim(),
            model = s.model.trim(),
            backend = s.backend.id,
            role = s.role,
            force = force,
        )
    }

    fun submit() = spawn(buildRequest(force = false))

    /** Re-submit after the 428 confirm dialog, bypassing the spawn gate. */
    fun confirmSpawn() {
        _state.update { it.copy(confirm = null) }
        spawn(buildRequest(force = true))
    }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    private fun spawn(req: SpawnRequest) {
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val outcome = repo.spawn(req)) {
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
            CreateAgentViewModel(repo) as T
    }
}
