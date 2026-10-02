package com.nivara.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.permissions.BatteryOptimizationRepository
import com.nivara.app.domain.permissions.BatteryOptimizationStatus
import com.nivara.app.domain.permissions.NotificationCapability
import com.nivara.app.domain.permissions.NotificationCapabilityRepository
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of the first-run permission gate.
 *
 * Three capabilities are required for Nivara to do its job — Usage Access, overlay and the
 * battery exemption — and one is optional: the notification permission for App Lock's quiet
 * notification. The gate is shown until the three required ones are all granted, and is never
 * shown again once they are; every answer is re-read whenever the user returns to Nivara, so a
 * grant made in Android's settings is noticed without a manual refresh.
 */
data class OnboardingUiState(
    val usageAccess: UsageAccessStatus = UsageAccessStatus.Unavailable,
    val overlay: OverlayCapability = OverlayCapability.Unavailable,
    val battery: BatteryOptimizationStatus = BatteryOptimizationStatus.Unavailable,
    val notifications: NotificationCapability = NotificationCapability.Unavailable,
    val checking: Boolean = true,
    val failure: NivaraMessage? = null,
) {
    /** The gate disappears exactly when every required capability is granted. */
    val requiredGranted: Boolean
        get() = usageAccess == UsageAccessStatus.Granted &&
            overlay == OverlayCapability.Granted &&
            battery == BatteryOptimizationStatus.Granted
}

/**
 * The gate's state machine: reads the three required capabilities and the optional notification
 * permission, and opens the platform surface where each required one is granted.
 *
 * It owns no grant of its own and never claims one: every row is a fresh platform answer, a
 * button only opens Android's own screen or dialog, and a failure to open one is reported
 * rather than swallowed. The one runtime dialog — notifications — is launched by the screen
 * through the platform's activity-result contract; this model only reports its state.
 */
class OnboardingViewModel(
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val batteryOptimizationRepository: BatteryOptimizationRepository,
    private val notificationCapabilityRepository: NotificationCapabilityRepository,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(OnboardingUiState())

    /** Current state of the gate, collected by the UI. */
    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads every capability. Called on creation and on every return to the foreground. */
    fun refresh() {
        viewModelScope.launch {
            val usage = usageAccessRepository.status()
            val overlay = overlayCapabilityRepository.status()
            val battery = batteryOptimizationRepository.status()
            val notifications = notificationCapabilityRepository.status()
            mutableUiState.value = OnboardingUiState(
                usageAccess = usage,
                overlay = overlay,
                battery = battery,
                notifications = notifications,
                checking = false,
            )
        }
    }

    /** Opens Android's Usage Access screen. */
    fun openUsageSettings() {
        act { usageAccessRepository.openSettings() }
    }

    /** Opens Android's overlay screen. */
    fun openOverlaySettings() {
        act { overlayCapabilityRepository.openSettings() }
    }

    /** Shows Android's battery-exemption confirmation, or its settings fallback. */
    fun requestBatteryExemption() {
        act { batteryOptimizationRepository.requestExemption() }
    }

    private fun act(open: suspend () -> NivaraResult<Unit>) {
        viewModelScope.launch {
            val result = open()
            mutableUiState.value = mutableUiState.value.copy(
                failure = if (result is NivaraResult.Failure) {
                    NivaraMessage(R.string.onboarding_settings_unavailable)
                } else {
                    null
                },
            )
        }
    }

    companion object {

        /** Factory supplying the gate's dependencies from the application container. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                OnboardingViewModel(
                    usageAccessRepository = container.usageAccessRepository,
                    overlayCapabilityRepository = container.overlayCapabilityRepository,
                    batteryOptimizationRepository = container.batteryOptimizationRepository,
                    notificationCapabilityRepository = container.notificationCapabilityRepository,
                )
            }
        }
    }
}
