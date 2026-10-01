package com.nivara.app.ui.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.isSuccess
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.AppLockProtectionRunner
import com.nivara.app.domain.applock.AppLockState
import com.nivara.app.domain.permissions.AppLockSetupState
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The App Lock preparation screen's state machine.
 *
 * It answers the questions a person has before App Lock can work at all: what Nivara can see on the
 * device, which capabilities Android has granted, what is still missing, and whether protection is
 * currently running. It decides nothing about protection itself — the switch below only starts and
 * stops the component that owns detection, and the state of that component is read back from it.
 *
 * Both capabilities are re-read when the screen is resumed, because the only way to grant either is
 * in Android's own settings: Nivara is stopped while those screens are open, and the answers are
 * different when it comes back. Nothing here grants anything, and a successful "open settings" is
 * never treated as a grant.
 */
class AppLockSetupViewModel(
    private val applicationRepository: ApplicationRepository,
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val protectionRunner: AppLockProtectionRunner,
    private val monitor: AppLockMonitor,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<AppLockSetupUiState>(AppLockSetupUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<AppLockSetupUiState> = mutableUiState.asStateFlow()

    /** The load in flight, if any. A later load replaces it rather than racing it. */
    private var loadJob: Job? = null

    /** `true` while the user is expected to be in Android's Usage Access screen. */
    private var awaitingUsageAccessReturn = false

    /** `true` while the user is expected to be in Android's overlay-permission screen. */
    private var awaitingOverlayReturn = false

    init {
        refresh()
        // Protection is owned by a component with its own lifecycle, so its state is read back
        // rather than assumed: turning the switch on and having the platform refuse would otherwise
        // leave the screen claiming protection that does not exist.
        viewModelScope.launch {
            monitor.state.collect { monitored -> applyProtectionState(monitored) }
        }
    }

    /** Reloads every capability, showing the loading state. Bound to the retry action. */
    fun refresh() {
        load(showLoading = true)
    }

    /**
     * Re-reads every capability whenever the screen comes back to the foreground, keeping what is
     * already drawn on screen until the new answers arrive.
     *
     * Called on every resume, including the one that accompanies the first composition. That first
     * call repeats the load started at construction, which costs a few queries and removes any
     * dependence on when the framework decides to deliver the callback — a return from Android's
     * settings screen is never the call that gets skipped.
     */
    fun onResumed() {
        load(showLoading = false)
    }

    /**
     * Opens Android's Usage Access settings.
     *
     * A success only means the screen was opened; the grant is read again through [onResumed]. A
     * failure is reported as a message, and the state on screen is left as it was.
     */
    fun openUsageAccessSettings() {
        openSettings(
            open = { usageAccessRepository.openSettings().isSuccess },
            markAwaiting = { awaitingUsageAccessReturn = true },
        )
    }

    /**
     * Opens Android's overlay-permission settings for Nivara.
     *
     * The same contract as above: opening the screen proves nothing about the grant, which is read
     * again when the user returns.
     */
    fun openOverlaySettings() {
        openSettings(
            open = { overlayCapabilityRepository.openSettings().isSuccess },
            markAwaiting = { awaitingOverlayReturn = true },
        )
    }

    /**
     * Starts protection, if every prerequisite is in place.
     *
     * The guard is not a permission check — it is the same aggregate the screen is showing, so the
     * button can never start something the screen says is unprepared. Protection that cannot run
     * because a capability is missing still reports that through detection's own state.
     *
     * A platform refusal is shown as a failure, and nothing else is claimed: the switch keeps
     * showing whatever the component that owns protection actually reports, which after a refusal is
     * that protection is not running. Reporting success here would be reporting something this
     * layer cannot know.
     */
    fun startProtection() {
        val current = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        if (!current.setup.isReady || current.busy) return
        mutableUiState.value = current.copy(busy = true, failure = null, noticeRes = null)
        val started = protectionRunner.start().isSuccess
        val state = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        mutableUiState.value = state.copy(
            busy = false,
            failure = if (started) null else protectionUnavailableMessage(),
        )
    }

    /** Stops protection. Always allowed: turning something off never needs a prerequisite. */
    fun stopProtection() {
        val current = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        if (current.busy) return
        mutableUiState.value = current.copy(busy = true, failure = null, noticeRes = null)
        val stopped = protectionRunner.stop().isSuccess
        val state = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        mutableUiState.value = state.copy(
            busy = false,
            // A stop that the platform refused leaves the component running, which the state read
            // back from it keeps showing; the failure is reported so the user is not left guessing
            // why the switch did not move.
            failure = if (stopped) null else protectionUnavailableMessage(),
        )
    }

    private fun openSettings(open: suspend () -> Boolean, markAwaiting: () -> Unit) {
        val current = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        mutableUiState.value = current.copy(busy = true, failure = null)
        viewModelScope.launch {
            val opened = open()
            val state = mutableUiState.value as? AppLockSetupUiState.Ready ?: return@launch
            if (opened) {
                markAwaiting()
                mutableUiState.value = state.copy(busy = false, failure = null, noticeRes = null)
            } else {
                mutableUiState.value = state.copy(
                    busy = false,
                    failure = usageAccessSettingsUnavailableMessage(),
                )
            }
        }
    }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = AppLockSetupUiState.Loading
            }
            val previous = mutableUiState.value as? AppLockSetupUiState.Ready
            mutableUiState.value = try {
                readState(clearFailure = showLoading, previous = previous)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The repositories report failures instead of throwing; a contract violation must
                // still not take the screen down, so it becomes the retryable error state.
                AppLockSetupUiState.Error
            }
        }
    }

    private suspend fun readState(
        clearFailure: Boolean,
        previous: AppLockSetupUiState.Ready?,
    ): AppLockSetupUiState.Ready {
        val discovery = applicationRepository.installedApplications()
        val usageAccess = usageAccessRepository.status()
        val overlay = overlayCapabilityRepository.status()
        return AppLockSetupUiState.Ready(
            setup = AppLockSetupState.of(
                discovery = discovery,
                usageAccess = usageAccess,
                overlay = overlay,
            ),
            protection = protectionRunState(monitor.state.value),
            busy = previous?.busy ?: false,
            failure = if (clearFailure) null else previous?.failure,
            noticeRes = capabilityReturnNotice(usageAccess = usageAccess, overlay = overlay),
        )
    }

    /** Keeps the drawn state in step with the component that actually owns protection. */
    private fun applyProtectionState(monitored: AppLockState) {
        val current = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        mutableUiState.value = current.copy(protection = protectionRunState(monitored))
    }

    /**
     * The one-shot confirmation shown when the user comes back from Android's settings with a grant
     * in place. Returning without granting is not an error and produces no message.
     */
    private fun capabilityReturnNotice(
        usageAccess: UsageAccessStatus,
        overlay: OverlayCapability,
    ): Int? {
        if (awaitingUsageAccessReturn) {
            awaitingUsageAccessReturn = false
            if (usageAccess == UsageAccessStatus.Granted) {
                return R.string.applock_setup_usage_access_granted_notice
            }
        }
        if (awaitingOverlayReturn) {
            awaitingOverlayReturn = false
            if (overlay == OverlayCapability.Granted) {
                return R.string.applock_setup_overlay_granted_notice
            }
        }
        return null
    }

    companion object {

        /**
         * Factory that supplies the dependencies of [AppLockSetupViewModel] from the application
         * container. Used by `viewModel(factory = …)` at the call site.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                AppLockSetupViewModel(
                    applicationRepository = container.applicationRepository,
                    usageAccessRepository = container.usageAccessRepository,
                    overlayCapabilityRepository = container.overlayCapabilityRepository,
                    protectionRunner = container.appLockProtectionRunner,
                    monitor = container.appLockMonitor,
                )
            }
        }
    }
}

/** How detection's state reads on the preparation screen. */
internal fun protectionRunState(monitored: AppLockState): ProtectionRunState = when (monitored) {
    AppLockState.Stopped -> ProtectionRunState.Stopped
    is AppLockState.Monitoring -> ProtectionRunState.Running
    is AppLockState.Unavailable -> ProtectionRunState.WithoutDecision
}
