package com.nivara.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.core.common.fold
import com.nivara.app.domain.security.DeviceSecurityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Presents the home screen.
 *
 * The view model depends on the [DeviceSecurityProvider] contract rather than on an Android
 * class, which keeps it testable on the JVM and independent of how the platform reports the
 * device state.
 */
class HomeViewModel(
    private val deviceSecurityProvider: DeviceSecurityProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** Loads the device security status, showing the loading state while it is in flight. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            _uiState.value = deviceSecurityProvider.isDeviceLockConfigured().fold(
                onSuccess = { lockConfigured -> HomeUiState.Ready(deviceLockConfigured = lockConfigured) },
                onFailure = { HomeUiState.Error },
            )
        }
    }

    companion object {
        /**
         * Factory that supplies the dependencies of [HomeViewModel] from the application
         * container. Used by `viewModel(factory = …)` at the call site.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                HomeViewModel(deviceSecurityProvider = application.container.deviceSecurityProvider)
            }
        }
    }
}
