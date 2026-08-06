package com.warden.android.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.warden.android.data.Connection
import com.warden.android.data.ConnectionResult
import com.warden.android.data.WardenRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient status of the "Test connection" probe. */
sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Ok(val count: Int) : TestState
    data class Error(val message: String) : TestState
}

data class ConnectUiState(
    val host: String = "",
    val token: String = "",
    val test: TestState = TestState.Idle,
) {
    val canTest: Boolean get() = host.isNotBlank() && token.isNotBlank() && test != TestState.Testing
}

class ConnectViewModel(
    private val repo: WardenRepository,
    prefill: Boolean = true,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    init {
        // Pre-fill from the active saved connection so re-editing is easy. When
        // adding a NEW host from the drawer we start blank instead (prefill=false)
        // so the active host's token isn't carried over.
        if (prefill) {
            repo.active?.let { c ->
                _state.update { it.copy(host = c.baseUrl, token = c.token) }
            }
        }
    }

    fun onHostChange(value: String) = _state.update { it.copy(host = value, test = TestState.Idle) }

    fun onTokenChange(value: String) = _state.update { it.copy(token = value, test = TestState.Idle) }

    /**
     * Runs the probe. On success persists+activates the connection and invokes
     * [onConnected] so the caller can navigate to the agent list.
     */
    fun test(onConnected: () -> Unit) {
        val s = _state.value
        val baseUrl = Connection.normalizeBaseUrl(s.host)
        if (baseUrl == null) {
            _state.update { it.copy(test = TestState.Error("Enter a host address")) }
            return
        }
        val connection = Connection(label = baseUrl, baseUrl = baseUrl, token = s.token.trim())

        _state.update { it.copy(test = TestState.Testing) }
        viewModelScope.launch {
            val result = repo.testConnection(connection)
            when (result) {
                is ConnectionResult.Success -> {
                    repo.activate(connection)
                    _state.update { it.copy(test = TestState.Ok(result.count)) }
                    onConnected()
                }
                is ConnectionResult.Unauthorized ->
                    _state.update { it.copy(test = TestState.Error("Token rejected (401). Check the passkey.")) }
                is ConnectionResult.Unreachable ->
                    _state.update { it.copy(test = TestState.Error("Can't reach daemon: ${result.message}")) }
                is ConnectionResult.HttpError ->
                    _state.update { it.copy(test = TestState.Error("Unexpected HTTP ${result.code} on /${result.where}")) }
            }
        }
    }

    class Factory(
        private val repo: WardenRepository,
        private val prefill: Boolean = true,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectViewModel(repo, prefill) as T
    }
}
