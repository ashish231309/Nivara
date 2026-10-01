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
import com.nivara.app.domain.permissions.AppLockSetupState
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
 * It answers three questions and nothing more: what Nivara can see on the device, whether Android
 * has granted Usage Access, and what the user can do about it. The view model depends on the domain
 * contracts, so it is testable on the JVM and no composable ever touches the package manager or the
 * usage-stats services.
 *
 * Usage Access is re-read when the screen is resumed, because the only way to grant it is in
 * Android's own settings screen: Nivara is stopped while that screen is open, and the answer is
 * different when it comes back. Nothing here grants anything, and a successful "open settings" is
 * never treated as a grant.
 */
class AppLockSetupViewModel(
    private val applicationRepository: ApplicationRepository,
    private val usageAccessRepository: UsageAccessRepository,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<AppLockSetupUiState>(AppLockSetupUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<AppLockSetupUiState> = mutableUiState.asStateFlow()

    /** The load in flight, if any. A later load replaces it rather than racing it. */
    private var loadJob: Job? = null

    /** `true` while the user is expected to be in Android's Usage Access screen. */
    private var awaitingUsageAccessReturn = false

    init {
        refresh()
    }

    /** Reloads both capabilities, showing the loading state. Bound to the retry action. */
    fun refresh() {
        load(showLoading = true)
    }

    /**
     * Re-reads both capabilities whenever the screen comes back to the foreground, keeping what is
     * already drawn on screen until the new answer arrives.
     *
     * Called on every resume, including the one that accompanies the first composition. That first
     * call repeats the load started at construction, which costs one query and removes any
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
        val current = mutableUiState.value as? AppLockSetupUiState.Ready ?: return
        mutableUiState.value = current.copy(busy = true, failure = null)
        viewModelScope.launch {
            val opened = usageAccessRepository.openSettings().isSuccess
            val state = mutableUiState.value as? AppLockSetupUiState.Ready ?: return@launch
            if (opened) {
                awaitingUsageAccessReturn = true
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

    private fun readState(
        clearFailure: Boolean,
        previous: AppLockSetupUiState.Ready?,
    ): AppLockSetupUiState.Ready {
        val discovery = applicationRepository.installedApplications()
        val usageAccess = usageAccessRepository.status()
        return AppLockSetupUiState.Ready(
            setup = AppLockSetupState.of(discovery = discovery, usageAccess = usageAccess),
            busy = previous?.busy ?: false,
            failure = if (clearFailure) null else previous?.failure,
            noticeRes = usageAccessReturnNotice(usageAccess),
        )
    }

    /**
     * The one-shot confirmation shown when the user comes back from Android's settings with the
     * grant in place. Returning without granting is not an error and produces no message.
     */
    private fun usageAccessReturnNotice(usageAccess: UsageAccessStatus): Int? {
        if (!awaitingUsageAccessReturn) return null
        awaitingUsageAccessReturn = false
        return if (usageAccess == UsageAccessStatus.Granted) {
            R.string.applock_setup_usage_access_granted_notice
        } else {
            null
        }
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
                AppLockSetupViewModel(
                    applicationRepository = application.container.applicationRepository,
                    usageAccessRepository = application.container.usageAccessRepository,
                )
            }
        }
    }
}
