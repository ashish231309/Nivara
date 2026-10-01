package com.nivara.app.ui.apphide.management

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.NivaraResult
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.ApplicationSearch
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.app.inDefaultApplicationOrder
import com.nivara.app.domain.app.inReverseApplicationOrder
import com.nivara.app.domain.apphide.ApplicationVisibility
import com.nivara.app.domain.apphide.HiddenApplication
import com.nivara.app.domain.apphide.HiddenApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.security.SessionManager
import com.nivara.app.ui.applications.ApplicationSortOrder
import com.nivara.app.ui.components.NivaraMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The hidden-application management screen's state machine.
 *
 * ### What it is responsible for
 *
 * Showing the device's applications, what the stored hidden set says about each of them, and
 * carrying the user's hide/unhide decisions to the repository that owns that set — the same
 * repository Nivara's own launcher will read. It owns no hidden state of its own: the list it holds
 * is a rendering of the last read, replaced on every read and never written back.
 *
 * ### What it deliberately does not do
 *
 * It does not search or order anything by hand: filtering is [ApplicationSearch] and ordering is the
 * domain's application ordering, both Android-free and both tested on their own. It does not
 * authenticate anyone: it asks the existing [SessionManager] whether the gate is open and refuses a
 * change when it is not — the credential and biometric paths that open the gate belong to the
 * screens that already implement them. And it knows nothing about App Lock: hiding and protecting
 * are separate dimensions, configured on separate screens, and nothing here reads, writes or depends
 * on the protected set.
 *
 * ### Why a change is refused while the gate is closed
 *
 * Unhiding an application puts it back in front of whoever is holding the phone. Anyone could
 * otherwise open Nivara, unhide an application and then open it. A change therefore requires the
 * same session every other sensitive action requires, opened through the existing credential or
 * biometric flow and never through a password of hiding's own. Reading the list does not require
 * one: it shows what the device's launcher already shows to anyone holding the phone.
 *
 * ### Refresh behaviour
 *
 * The screen reads when it is created, whenever it is resumed, and after every change. There is no
 * polling and no timer; the one thing that changes on its own — the session — is observed as a flow
 * rather than re-read.
 */
class HiddenManagementViewModel(
    private val applicationRepository: ApplicationRepository,
    private val hiddenApplicationRepository: HiddenApplicationRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<HiddenManagementUiState>(HiddenManagementUiState.Loading)

    /** Current state of the screen, collected by the UI. */
    val uiState: StateFlow<HiddenManagementUiState> = mutableUiState.asStateFlow()

    /** The read in flight, if any. A later read replaces it rather than racing it. */
    private var loadJob: Job? = null

    /** The last successful read, or `null` before the first one. The only cache the screen keeps. */
    private var loaded: Loaded? = null

    private var query: String = ""
    private var sort: ApplicationSortOrder = ApplicationSortOrder.NameAscending
    private var section: HiddenSection = HiddenSection.All
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
    }

    /** Reloads the applications and the stored hidden set, showing the loading state. */
    fun refresh() {
        load(showLoading = true)
    }

    /**
     * Re-reads everything when the screen comes back to the foreground, keeping what is drawn until
     * the new answers arrive.
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

    /** Shows all applications or only the hidden ones. */
    fun onSectionChange(section: HiddenSection) {
        this.section = section
        publish()
    }

    /** Adds [application] to the stored hidden set. */
    fun hide(application: InstalledApplication) {
        change(application.packageName) { hidden -> hiddenApplicationRepository.hide(hidden) }
    }

    /** Removes [application] from the stored hidden set. */
    fun unhide(application: InstalledApplication) {
        change(application.packageName) { hidden -> hiddenApplicationRepository.unhide(hidden) }
    }

    /**
     * Applies one change to the stored set, followed by a fresh read of everything.
     *
     * Four refusals happen before anything is written, and each is a fact rather than a guess: a
     * change is already running, the gate is closed, the stored set could not be read, or the
     * application is not in the catalogue that was read a moment ago. The last one is what keeps a
     * stale row from writing a name the device no longer has.
     */
    private fun change(
        packageName: String,
        applyToRepository: suspend (HiddenApplication) -> NivaraResult<Unit>,
    ) {
        val current = mutableUiState.value
        if (current !is HiddenManagementUiState.Ready || current.busy) return

        if (!sessionManager.currentState().isAuthenticated) {
            failure = hiddenLockedMessage()
            noticeRes = null
            publish()
            return
        }

        val hiddenRead = loaded?.hiddenRead
        if (hiddenRead !is HiddenApplicationsRead.Available) {
            // The stored set could not be read, so a change cannot be reasoned about: hiding is never
            // written to a configuration whose current contents are unknown. The screen already
            // disables the control in this situation; refusing here as well means the rule holds even
            // if the tap arrives from somewhere else.
            failure = when (hiddenRead) {
                HiddenApplicationsRead.Unavailable -> hiddenStateUnavailableMessage()
                else -> hiddenStateUnreadableMessage()
            }
            noticeRes = null
            publish()
            return
        }

        if (loaded?.catalogue?.any { it.packageName == packageName } != true) {
            // The row is stale: discovery has already shown that this application is gone. Nothing is
            // written, and the list is re-read so the row disappears. An application that is merely
            // undiscovered for a moment keeps its stored entry — this refusal writes nothing at all.
            failure = hiddenApplicationNotInstalledMessage()
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
            val result = try {
                applyToRepository(HiddenApplication(packageName))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The repository reports failures instead of throwing; a contract violation must
                // still not take the screen down, and it must not be reported as a stored change.
                NivaraResult.Failure(error)
            }
            // The outcome of a write is never assumed: everything is read back from the repositories
            // afterwards, so the list shows what is stored rather than what was asked for.
            busy = false
            failure = if (result.isSuccess) null else hiddenChangeNotStoredMessage()
            noticeRes = if (result.isSuccess) R.string.apphide_manage_notice_changed else null
            load(showLoading = false)
        }
    }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = HiddenManagementUiState.Loading
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
                // and the failure is reported instead. The stored hidden set is untouched either way:
                // a discovery problem must never unhide anything.
                if (loaded == null) {
                    mutableUiState.value = HiddenManagementUiState.Error
                } else {
                    failure = hiddenDiscoveryUnavailableMessage()
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
     * `null` means the device's applications could not be discovered at all. The hidden set is read
     * separately, and its own failure cases travel as cases rather than as an exception, so a damaged
     * file reaches the screen as "unreadable" and never as "nothing is hidden".
     */
    private suspend fun read(): Loaded? {
        val catalogue = applicationRepository.installedApplications().valueOrNull() ?: return null
        val hiddenRead = hiddenApplicationRepository.hiddenApplications()

        val discoveredNames = catalogue.mapTo(mutableSetOf()) { it.packageName }
        val hidden = (hiddenRead as? HiddenApplicationsRead.Available)?.hidden
        val notInstalled = hidden?.count { application -> application.packageName !in discoveredNames } ?: 0

        return Loaded(
            catalogue = catalogue.inDefaultApplicationOrder(),
            hiddenRead = hiddenRead,
            hiddenNotInstalledCount = notInstalled,
        )
    }

    /** Rebuilds the drawn state from the last read, without touching any repository. */
    private fun publish() {
        val data = loaded ?: return
        val hiddenRead = data.hiddenRead

        val visible = data.catalogue.filter { application ->
            when {
                // With no readable hidden set, "hidden only" cannot be answered, so the section shows
                // nothing and the screen explains why rather than listing everything as if it were
                // the hidden set — or, worse, showing nothing as if nothing were hidden.
                hiddenRead !is HiddenApplicationsRead.Available -> section == HiddenSection.All

                section == HiddenSection.All -> true

                else -> hiddenRead.visibilityOf(application.packageName) == ApplicationVisibility.Hidden
            }
        }

        val matching = ApplicationSearch.filter(visible, query)
        val ordered = when (sort) {
            ApplicationSortOrder.NameAscending -> matching.inDefaultApplicationOrder()
            ApplicationSortOrder.NameDescending -> matching.inReverseApplicationOrder()
        }

        mutableUiState.value = HiddenManagementUiState.Ready(
            rows = ordered.map { application ->
                ManagedHiddenApplication(
                    application = application,
                    visibility = hiddenRead.visibilityOf(application.packageName),
                )
            },
            section = section,
            sort = sort,
            query = query,
            hiddenState = when (hiddenRead) {
                is HiddenApplicationsRead.Available -> HiddenStateAvailability.Available(
                    hiddenCount = hiddenRead.hidden.size,
                    notInstalledCount = data.hiddenNotInstalledCount,
                )

                HiddenApplicationsRead.Unreadable -> HiddenStateAvailability.Unreadable

                HiddenApplicationsRead.Unavailable -> HiddenStateAvailability.Unavailable
            },
            sessionAuthenticated = sessionManager.currentState().isAuthenticated,
            discoveredCount = data.catalogue.size,
            busy = busy,
            failure = failure,
            noticeRes = noticeRes,
            emptiness = emptinessOf(data, visible, matching),
        )
    }

    /**
     * Why the drawn list is empty, or `null` when it is not.
     *
     * Each case is a fact the screen can state plainly, and none of them is ever "nothing is hidden"
     * when the truth is something else: a hidden set that could not be read and one that is
     * legitimately empty look identical once rendered as rows, and that is exactly the confusion this
     * name prevents.
     */
    private fun emptinessOf(
        data: Loaded,
        visible: List<InstalledApplication>,
        rows: List<InstalledApplication>,
    ): HiddenListEmptiness? = when {
        rows.isNotEmpty() -> null

        data.hiddenRead is HiddenApplicationsRead.Unreadable -> HiddenListEmptiness.HiddenStateUnreadable

        data.hiddenRead is HiddenApplicationsRead.Unavailable -> HiddenListEmptiness.HiddenStateUnavailable

        data.catalogue.isEmpty() -> HiddenListEmptiness.DeviceHasNoApplications

        // The section had something to show and the query is what emptied it: that is a search
        // result. Saying that nothing is hidden yet would be a different, and false, statement.
        section == HiddenSection.Hidden && visible.isEmpty() -> HiddenListEmptiness.NothingHidden

        else -> HiddenListEmptiness.NoSearchResults
    }

    /** One successful read: the catalogue and the stored hidden set, read together. */
    private data class Loaded(
        val catalogue: List<InstalledApplication>,
        val hiddenRead: HiddenApplicationsRead,
        val hiddenNotInstalledCount: Int,
    )

    companion object {

        /**
         * Factory that supplies the dependencies of [HiddenManagementViewModel] from the application
         * container. Used by `viewModel(factory = …)` at the call site.
         *
         * Three dependencies, and no App Lock one among them: hiding and protecting are separate
         * dimensions, and this screen cannot reach the protected set even by accident.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                HiddenManagementViewModel(
                    applicationRepository = container.applicationRepository,
                    hiddenApplicationRepository = container.hiddenApplicationRepository,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}
