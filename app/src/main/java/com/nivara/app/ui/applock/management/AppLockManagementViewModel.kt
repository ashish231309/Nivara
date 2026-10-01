package com.nivara.app.ui.applock.management

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.fold
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.ApplicationSearch
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.app.inDefaultApplicationOrder
import com.nivara.app.domain.app.inReverseApplicationOrder
import com.nivara.app.domain.applock.AppLockMonitor
import com.nivara.app.domain.applock.ApplicationProtectionState
import com.nivara.app.domain.applock.ProtectedApplication
import com.nivara.app.domain.applock.ProtectedApplicationRepository
import com.nivara.app.domain.permissions.AppLockPrerequisite
import com.nivara.app.domain.permissions.OverlayCapability
import com.nivara.app.domain.permissions.OverlayCapabilityRepository
import com.nivara.app.domain.permissions.UsageAccessRepository
import com.nivara.app.domain.permissions.UsageAccessStatus
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.applock.protectionRunState
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The App Lock management screen's state machine.
 *
 * ### What it is responsible for
 *
 * Showing the device's applications, what the stored protected set says about each of them, and
 * carrying the user's protect/unprotect decisions to the repository that owns that set — the same
 * repository the detection service reads. It owns no protected state of its own: the list it holds
 * is a rendering of the last read, replaced on every read and never written back.
 *
 * ### What it deliberately does not do
 *
 * It does not search or order anything by hand: filtering is [ApplicationSearch] and ordering is the
 * domain's application ordering, both Android-free and both tested on their own. It does not check
 * permissions: the capability answers come from the same repositories the preparation screen uses.
 * It does not authenticate anyone: it asks the existing [SessionManager] whether the gate is open
 * and refuses a change when it is not — the credential and biometric paths that open the gate belong
 * to the screens that already implement them.
 *
 * ### Why a change is refused while the gate is closed
 *
 * Unprotecting an application is the one action that can undo App Lock for that application. Anyone
 * holding an unlocked phone could otherwise open Nivara, remove a protected application from the set
 * and then open that application. A change therefore requires the same session every other sensitive
 * action requires, opened through the existing credential or biometric flow and never through a
 * password of App Lock's own. Reading the list does not require one: it shows what the device's
 * launcher already shows to anyone holding the phone.
 *
 * ### Refresh behaviour
 *
 * The screen reads when it is created, whenever it is resumed — which is how a return from Android's
 * settings is noticed — and after every change. There is no polling and no timer; the two things
 * that change on their own, the session and the component that owns protection, are observed as
 * flows rather than re-read.
 */
class AppLockManagementViewModel(
    private val applicationRepository: ApplicationRepository,
    private val protectedApplicationRepository: ProtectedApplicationRepository,
    private val usageAccessRepository: UsageAccessRepository,
    private val overlayCapabilityRepository: OverlayCapabilityRepository,
    private val sessionManager: SessionManager,
    private val monitor: AppLockMonitor,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<AppLockManagementUiState>(AppLockManagementUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<AppLockManagementUiState> = mutableUiState.asStateFlow()

    /** The read in flight, if any. A later read replaces it rather than racing it. */
    private var loadJob: Job? = null

    /** The last successful read, or `null` before the first one. The only cache the screen keeps. */
    private var loaded: Loaded? = null

    private var query: String = ""
    private var sort: ApplicationSortOrder = ApplicationSortOrder.NameAscending
    private var section: ApplicationSection = ApplicationSection.All
    private var busy: Boolean = false
    private var failure: NivaraMessage? = null
    private var noticeRes: Int? = null

    init {
        load(showLoading = true)
        // The gate closes on its own — a timeout, or Quick Lock from anywhere in the application —
        // and it is the authority on whether a change may be made. Watching it keeps the controls
        // honest without the screen ever deciding for itself.
        viewModelScope.launch {
            sessionManager.state.collect { publish() }
        }
        // Protection can be started, stopped or lose a prerequisite while this screen is open.
        viewModelScope.launch {
            monitor.state.collect { publish() }
        }
    }

    /** Reloads the applications, the protected set and the capabilities, showing the loading state. */
    fun refresh() {
        load(showLoading = true)
    }

    /**
     * Re-reads everything when the screen comes back to the foreground, keeping what is drawn until
     * the new answers arrive. This is what notices a return from Android's settings screens.
     */
    fun onResumed() {
        load(showLoading = false)
    }

    /** Applies a search query. Nothing is written anywhere; this only re-filters what was read. */
    fun onQueryChange(query: String) {
        this.query = query
        publish()
    }

    /** Applies an ordering. Nothing is written anywhere; the stored set is not touched. */
    fun onSortChange(sort: ApplicationSortOrder) {
        this.sort = sort
        publish()
    }

    /** Shows all applications or only the protected ones. */
    fun onSectionChange(section: ApplicationSection) {
        this.section = section
        publish()
    }

    /** Adds [application] to the stored protected set. */
    fun protect(application: InstalledApplication) {
        change(application.packageName) { protected -> protectedApplicationRepository.protect(protected) }
    }

    /** Removes [application] from the stored protected set. */
    fun unprotect(application: InstalledApplication) {
        change(application.packageName) { protected -> protectedApplicationRepository.unprotect(protected) }
    }

    /**
     * Applies one change to the stored set, followed by a fresh read of everything.
     *
     * Three refusals happen before anything is written, and each is a fact rather than a guess: a
     * change is already running, the gate is closed, or the application is not in the catalogue that
     * was read a moment ago. The last one is what keeps a stale row from writing a name the device
     * no longer has.
     */
    private fun change(
        packageName: String,
        applyToRepository: suspend (ProtectedApplication) -> NivaraResult<Unit>,
    ) {
        val current = mutableUiState.value
        if (current !is AppLockManagementUiState.Ready || current.busy) return

        if (!sessionManager.currentState().isAuthenticated) {
            failure = lockedMessage()
            noticeRes = null
            publish()
            return
        }

        if (loaded?.snapshot?.storedSetUnreadable == true) {
            // The stored set could not be read, so a change cannot be reasoned about: protection is
            // never written to a configuration whose current contents are unknown. The screen
            // already disables the control in this situation; refusing here as well means the rule
            // holds even if the tap arrives from somewhere else.
            failure = protectedSetUnreadableMessage()
            noticeRes = null
            publish()
            return
        }

        if (loaded?.catalogue?.any { it.packageName == packageName } != true) {
            // The row is stale: discovery has already shown that this application is gone. Nothing is
            // written, and the list is re-read so the row disappears.
            failure = applicationNotInstalledMessage()
            noticeRes = null
            publish()
            load(showLoading = false)
            return
        }

        busy = true
        failure = null
        noticeRes = null
        publish()

        viewModelScope.launch {
            val result = applyToRepository(ProtectedApplication(packageName))
            // The outcome of a write is never assumed: everything is read back from the repositories
            // afterwards, so the list shows what is stored rather than what was asked for.
            busy = false
            failure = if (result.isSuccess) null else changeNotStoredMessage()
            noticeRes = if (result.isSuccess) R.string.applock_manage_notice_changed else null
            load(showLoading = false)
        }
    }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = AppLockManagementUiState.Loading
            }
            val read = try {
                read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The repositories report failures instead of throwing; a contract violation must
                // still not take the screen down.
                null
            }
            if (read == null) {
                // The device's applications could not be discovered. If something was already on
                // screen it stays there — a failed refresh is not evidence that the device changed —
                // and the failure is reported instead. The stored protected set is untouched either
                // way: a discovery problem must never delete protection.
                if (loaded == null) {
                    mutableUiState.value = AppLockManagementUiState.Error
                } else {
                    failure = discoveryUnavailableMessage()
                    publish()
                }
                return@launch
            }

            loaded = read
            publish()
        }
    }

    /**
     * Reads every answer the screen draws from.
     *
     * `null` means the device's applications could not be discovered at all. The other answers are
     * always produced: a protected set that cannot be read becomes [ProtectedSetRead.Unreadable]
     * rather than an empty set, and a capability that cannot be read counts as unsatisfied rather
     * than granted.
     */
    private suspend fun read(): Loaded? {
        val catalogue = applicationRepository.installedApplications().valueOrNull() ?: return null

        val usageAccess = usageAccessRepository.status()
        val overlay = overlayCapabilityRepository.status()

        val protectedRead = protectedApplicationRepository.protectedApplications().fold(
            onSuccess = { applications -> ProtectedSetRead.Readable(applications.mapTo(mutableSetOf()) { it.packageName }) },
            onFailure = { ProtectedSetRead.Unreadable },
        )

        val missing = buildList {
            if (usageAccess != UsageAccessStatus.Granted) add(AppLockPrerequisite.UsageAccess)
            if (overlay != OverlayCapability.Granted) add(AppLockPrerequisite.Overlay)
        }

        val discoveredNames = catalogue.mapTo(mutableSetOf()) { it.packageName }
        val notInstalled = when (protectedRead) {
            is ProtectedSetRead.Readable -> protectedRead.packageNames.count { it !in discoveredNames }
            ProtectedSetRead.Unreadable -> 0
        }

        return Loaded(
            catalogue = catalogue.inDefaultApplicationOrder(),
            snapshot = ProtectionSnapshot(
                protected = protectedRead,
                requirementsSatisfied = missing.isEmpty(),
            ),
            missingPrerequisites = missing,
            protectedNotInstalledCount = notInstalled,
        )
    }

    /** Rebuilds the drawn state from the last read, without touching any repository. */
    private fun publish() {
        val data = loaded ?: return
        val snapshot = data.snapshot

        val visible = data.catalogue.filter { application ->
            when {
                // With no readable protected set, "protected only" cannot be answered, so the section
                // shows nothing and the screen explains why rather than listing everything as if it
                // were the protected set.
                snapshot.storedSetUnreadable -> section == ApplicationSection.All

                section == ApplicationSection.All -> true

                else -> snapshot.stateOf(application) != ApplicationProtectionState.NotProtected
            }
        }

        val matching = ApplicationSearch.filter(visible, query)
        val ordered = when (sort) {
            ApplicationSortOrder.NameAscending -> matching.inDefaultApplicationOrder()
            ApplicationSortOrder.NameDescending -> matching.inReverseApplicationOrder()
        }

        mutableUiState.value = AppLockManagementUiState.Ready(
            rows = ordered.map { application ->
                ManagedApplication(application = application, state = snapshot.stateOf(application))
            },
            section = section,
            sort = sort,
            query = query,
            missingPrerequisites = data.missingPrerequisites,
            runState = protectionRunState(monitor.state.value),
            storedSetUnreadable = snapshot.storedSetUnreadable,
            sessionAuthenticated = sessionManager.currentState().isAuthenticated,
            discoveredCount = data.catalogue.size,
            // Zero, not "everything", when the stored set could not be read: a count derived from an
            // unreadable set would be a number the screen invented. The screen does not show it in
            // that case either.
            protectedCount = if (snapshot.storedSetUnreadable) {
                0
            } else {
                data.catalogue.count { application ->
                    snapshot.stateOf(application) != ApplicationProtectionState.NotProtected
                }
            },
            protectedNotInstalledCount = data.protectedNotInstalledCount,
            busy = busy,
            failure = failure,
            noticeRes = noticeRes,
            emptiness = emptinessOf(data, visible, matching),
        )
    }

    /**
     * Why the drawn list is empty, or `null` when it is not.
     *
     * Each case is a fact the screen can state plainly, and none of them is ever "nothing is
     * protected" when the truth is something else: an unreadable set and an empty set look
     * identical once rendered as rows, and that is exactly the confusion this name prevents.
     */
    private fun emptinessOf(
        data: Loaded,
        visible: List<InstalledApplication>,
        rows: List<InstalledApplication>,
    ): AppLockListEmptiness? = when {
        rows.isNotEmpty() -> null

        data.snapshot.storedSetUnreadable -> AppLockListEmptiness.ProtectedSetUnreadable

        data.catalogue.isEmpty() -> AppLockListEmptiness.DeviceHasNoApplications

        // The section had something to show and the query is what emptied it: that is a search
        // result. Saying "nothing is protected yet" here would be a different, and false, statement.
        section == ApplicationSection.Protected && visible.isEmpty() ->
            AppLockListEmptiness.NothingProtected

        else -> AppLockListEmptiness.NoSearchResults
    }

    /** One successful read: the catalogue, what it means, and what is missing. */
    private data class Loaded(
        val catalogue: List<InstalledApplication>,
        val snapshot: ProtectionSnapshot,
        val missingPrerequisites: List<AppLockPrerequisite>,
        val protectedNotInstalledCount: Int,
    )

    companion object {

        /**
         * Factory that supplies the dependencies of [AppLockManagementViewModel] from the
         * application container. Used by `viewModel(factory = …)` at the call site.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                AppLockManagementViewModel(
                    applicationRepository = container.applicationRepository,
                    protectedApplicationRepository = container.protectedApplicationRepository,
                    usageAccessRepository = container.usageAccessRepository,
                    overlayCapabilityRepository = container.overlayCapabilityRepository,
                    sessionManager = container.sessionManager,
                    monitor = container.appLockMonitor,
                )
            }
        }
    }
}
