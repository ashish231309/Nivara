package com.nivara.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.credential.CredentialManager
import com.nivara.app.domain.credential.CredentialStatus
import com.nivara.app.domain.security.DeviceSecurityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presents the home screen.
 *
 * The view model depends on domain contracts — [DeviceSecurityProvider] and [CredentialManager] —
 * rather than on Android or data-layer classes, which keeps it testable on the JVM and independent
 * of how the platform reports device state or stores the credential.
 */
class HomeViewModel(
    private val deviceSecurityProvider: DeviceSecurityProvider,
    private val credentialManager: CredentialManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Loads the device security status and the credential status, showing the loading state. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            _uiState.value = load()
        }
    }

    private suspend fun load(): HomeUiState {
        val deviceLockConfigured = deviceSecurityProvider.isDeviceLockConfigured().valueOrNull()
            ?: return HomeUiState.Error
        val credentialStatus = credentialManager.status().valueOrNull()
            ?: return HomeUiState.Error

        val credentialType = when (credentialStatus) {
            is CredentialStatus.Configured -> credentialStatus.type
            is CredentialStatus.NotConfigured -> null
        }

        return HomeUiState.Ready(
            deviceLockConfigured = deviceLockConfigured,
            credentialType = credentialType,
        )
    }

    companion object {
        /**
         * Factory that supplies the dependencies of [HomeViewModel] from the application
         * container. Used by `viewModel(factory = …)` at the call site.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                HomeViewModel(
                    deviceSecurityProvider = application.container.deviceSecurityProvider,
                    credentialManager = application.container.credentialManager,
                )
            }
        }
    }
}
