package com.nivara.app.ui.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nivara.app.NivaraApplication
import com.nivara.app.R
import com.nivara.app.core.common.isSuccess
import com.nivara.app.core.common.valueOrNull
import com.nivara.app.domain.app.ApplicationLauncher
import com.nivara.app.domain.app.ApplicationRepository
import com.nivara.app.domain.app.ApplicationSearch
import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.app.inDefaultApplicationOrder
import com.nivara.app.domain.app.inReverseApplicationOrder
import com.nivara.app.domain.apphide.HiddenApplicationRepository
import com.nivara.app.domain.apphide.HiddenApplicationsRead
import com.nivara.app.domain.launcher.LauncherCatalogue
import com.nivara.app.domain.launcher.launcherCatalogue
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
 * The launcher's state machine.
 *
 * ### What it decides
 *
 * Which applications may be drawn, and whether hidden applications are being shown because an
 * authenticated user asked for them. The first is the domain's rule ([launcherCatalogue]); the
 * second is presentation state this class owns and nothing else does.
 *
 * ### What it deliberately does not do
 *
 * It does not read, write or parse the stored hidden set: it asks
 * [HiddenApplicationRepository] and works with one of that contract's three answers. It does not
 * authenticate anybody and keeps no session of its own: it asks the existing [SessionManager]
 * whether the gate is open, and sends the user to the existing credential screen when it is not.
 * It launches applications through [ApplicationLauncher] rather than building intents, and it awaits
 * the result so the drawer can refuse a second tap while the first is being started. And it knows
 * nothing about App Lock: protecting and hiding are separate dimensions, and nothing here reads or
 * changes the protected set.
 *
 * ### Why the fail-closed rule lives behind this class
 *
 * If the stored hidden set cannot be read, this view model never produces a [LauncherUiState.Ready]
 * at all. That is stronger than filtering defensively: there is no list for a screen to draw by
 * accident, no half-answer to reason about, and no path by which "I could not read what is hidden"
 * becomes "here is everything".
 *
 * ### Why the reveal is safe to hold in memory
 *
 * [revealHidden] is one boolean. It is cleared when the session gate closes — by expiry, by Quick
 * Lock, or by a failed authentication — and it dies with the process, so a recreated launcher has
 * nothing to restore and draws the normal drawer. It is never written anywhere, and hiding an
 * application is never undone by it: the repository's answer is unchanged, and the moment the
 * session ends the applications are withheld again.
 */
class LauncherViewModel(
    private val applicationRepository: ApplicationRepository,
    private val hiddenApplicationRepository: HiddenApplicationRepository,
    private val applicationLauncher: ApplicationLauncher,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<LauncherUiState>(LauncherUiState.Loading)

    /** Current state of the launcher, collected by the UI. */
    val uiState: StateFlow<LauncherUiState> = mutableUiState.asStateFlow()

    /** The read in flight, if any. A later read replaces it rather than racing it. */
    private var loadJob: Job? = null

    /** The last successful read, or `null` before the first one. The only cache the launcher keeps. */
    private var loaded: Loaded? = null

    private var query: String = ""
    private var sort: ApplicationSortOrder = ApplicationSortOrder.NameAscending
    private var section: LauncherSection = LauncherSection.All

    /** Presentation state, memory only. See the class documentation. */
    private var revealHidden: Boolean = false

    private var busy: Boolean = false
    private var unlockRequired: Boolean = false
    private var failure: NivaraMessage? = null
    private var noticeRes: Int? = null

    init {
        load(showLoading = true)
        // The gate closes on its own — a timeout, or Quick Lock from anywhere in Nivara — and it is
        // the authority on whether hidden applications may be shown. Watching it means the reveal
        // ends the moment the authorization that justified it ends, without this class keeping a
        // timer of its own.
        viewModelScope.launch {
            sessionManager.state.collect { session ->
                if (!session.isAuthenticated && revealHidden) {
                    revealHidden = false
                }
                publish()
            }
        }
    }

    /** Re-reads everything, showing the loading state. */
    fun refresh() {
        load(showLoading = true)
    }

    /**
     * Re-reads everything when the launcher comes back to the foreground, keeping what is drawn
     * until the new answers arrive.
     *
     * This is not a re-authentication and not an extension of anything: the session is read as it is,
     * so a launcher that is resumed a second after a Quick Lock stays locked.
     */
    fun onResumed() {
        load(showLoading = false)
    }

    /** Applies a drawer search query. Nothing is written anywhere; it only re-filters what was read. */
    fun onQueryChange(query: String) {
        this.query = query
        publish()
    }

    /** Applies an ordering. Nothing is written anywhere. */
    fun onSortChange(sort: ApplicationSortOrder) {
        this.sort = sort
        publish()
    }

    /** Shows every drawable application, or only the hidden ones while a reveal is active. */
    fun onSectionChange(section: LauncherSection) {
        this.section = section
        publish()
    }

    /**
     * Asks to see the hidden applications.
     *
     * With a valid session this only sets a presentation flag; without one it sets
     * [LauncherUiState.Ready.unlockRequired] and the screen sends the user to the credential screen.
     * Nothing is read, written or unhidden either way.
     */
    fun revealHiddenApplications() {
        val current = mutableUiState.value
        if (current !is LauncherUiState.Ready || current.hiddenCount == 0) return

        if (!sessionManager.currentState().isAuthenticated) {
            unlockRequired = true
            publish()
            return
        }

        revealHidden = true
        unlockRequired = false
        noticeRes = R.string.launcher_reveal_notice
        publish()
    }

    /** Stops showing hidden applications. The stored set is untouched. */
    fun concealHiddenApplications() {
        revealHidden = false
        section = LauncherSection.All
        noticeRes = null
        publish()
    }

    /** Called once the screen has sent the user to the credential screen. */
    fun onUnlockHandled() {
        if (!unlockRequired) return
        unlockRequired = false
        publish()
    }

    /** Clears the last failure or confirmation, so a message does not outlive the moment. */
    fun onMessageShown() {
        if (failure == null && noticeRes == null) return
        failure = null
        noticeRes = null
        publish()
    }

    /**
     * Starts [application].
     *
     * The row is checked against the catalogue that was last read first, so a tap on an application
     * that has been uninstalled since does not ask the platform to start something that is gone; the
     * list is re-read instead. Nothing about the application is changed by launching it.
     */
    fun launch(application: InstalledApplication) {
        val current = mutableUiState.value
        if (current !is LauncherUiState.Ready || current.busy) return

        if (loaded?.catalogue?.any { it.packageName == application.packageName } != true) {
            failure = launcherApplicationGoneMessage()
            noticeRes = null
            publish()
            load(showLoading = false)
            return
        }

        busy = true
        failure = null
        publish()

        // Starting an application is a platform call, so it happens off the main thread; `busy` is
        // set before the coroutine starts, which is what lets a second tap be refused while the
        // first is still being started.
        viewModelScope.launch {
            val result = try {
                applicationLauncher.launch(application.packageName)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // The contract reports failures instead of throwing; a violation of it must still
                // not take the launcher down, and must not be reported as a successful launch.
                null
            }

            busy = false
            if (result == null || !result.isSuccess) {
                failure = launcherLaunchRefusedMessage()
                publish()
                return@launch
            }
            // A launch that worked is not a change to this screen's data, so nothing is re-read:
            // the drawer stays exactly as it was, and the user is left in the application they
            // opened.
            publish()
        }
    }

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) {
                mutableUiState.value = LauncherUiState.Loading
            }
            val read = try {
                read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            }
            when (read) {
                null -> onLoadFailure()
                else -> {
                    loaded = read
                    publish()
                }
            }
        }
    }

    /**
     * The catalogue read failed.
     *
     * If something was already drawn it stays drawn — a failed refresh is not evidence that the
     * device changed — and the failure is reported. If nothing was drawn, the launcher says it could
     * not list the device. Neither path touches the stored hidden set.
     */
    private fun onLoadFailure() {
        if (loaded == null) {
            mutableUiState.value = LauncherUiState.DiscoveryUnavailable
        } else {
            failure = launcherDiscoveryUnavailableMessage()
            publish()
        }
    }

    /**
     * Reads everything the launcher draws from.
     *
     * `null` means the device's applications could not be discovered at all. The hidden set is read
     * separately and its failures travel as cases, so a damaged record is carried to the screen as
     * "unreadable" rather than as an empty set — and never as a list.
     */
    private suspend fun read(): Loaded? {
        val catalogue = applicationRepository.installedApplications().valueOrNull() ?: return null
        val hiddenRead = hiddenApplicationRepository.hiddenApplications()
        return Loaded(catalogue = catalogue.inDefaultApplicationOrder(), hiddenRead = hiddenRead)
    }

    /** Rebuilds the drawn state from the last read, without touching any repository. */
    private fun publish() {
        val data = loaded ?: return

        // The gate is asked before anything is drawn, and it is the authority on whether a reveal may
        // continue. Asking here rather than waiting for the state flow's emission means an expired
        // session re-hides hidden applications on the very read that noticed the expiry — the drawer
        // is never built from an authorization that has already run out.
        val authenticated = sessionManager.currentState().isAuthenticated
        if (!authenticated && revealHidden) {
            revealHidden = false
            if (section == LauncherSection.Hidden) {
                section = LauncherSection.All
            }
        }

        val catalogue = launcherCatalogue(
            discovered = data.catalogue,
            hidden = data.hiddenRead,
            revealHidden = revealHidden,
        )

        if (catalogue !is LauncherCatalogue.Loaded) {
            // Fail closed: the hidden set could not be read, so nothing is drawn at all. The reveal
            // is dropped with it — there is nothing to reveal from a set Nivara cannot read.
            revealHidden = false
            section = LauncherSection.All
            mutableUiState.value = when (catalogue) {
                LauncherCatalogue.HiddenStateUnreadable -> LauncherUiState.HiddenStateUnreadable
                else -> LauncherUiState.HiddenStateUnavailable
            }
            return
        }

        val revealed = catalogue.revealed
        if (!revealed && section == LauncherSection.Hidden) {
            // The hidden section exists only while a reveal does. When the session ends, the section
            // goes with it rather than staying open on an empty list.
            section = LauncherSection.All
        }

        val visible = when (section) {
            LauncherSection.All -> catalogue.entries
            LauncherSection.Hidden -> catalogue.hidden
        }
        val matching = ApplicationSearch.filter(visible, query)
        val ordered = when (sort) {
            ApplicationSortOrder.NameAscending -> matching.inDefaultApplicationOrder()
            ApplicationSortOrder.NameDescending -> matching.inReverseApplicationOrder()
        }

        mutableUiState.value = LauncherUiState.Ready(
            entries = ordered,
            section = section,
            sort = sort,
            query = query,
            revealed = revealed,
            sessionAuthenticated = authenticated,
            discoveredCount = data.catalogue.size,
            hiddenCount = catalogue.hiddenCount,
            withheldCount = catalogue.withheldCount,
            busy = busy,
            unlockRequired = unlockRequired,
            failure = failure,
            noticeRes = noticeRes,
            emptiness = emptinessOf(data, visible, matching),
        )
    }

    /**
     * Why the drawer is empty, or `null` when it is not.
     *
     * The four cases are separate statements about the device, and getting them confused would
     * mislead in both directions: "everything is hidden" and "your search found nothing" look the
     * same in an empty grid.
     */
    private fun emptinessOf(
        data: Loaded,
        visible: List<InstalledApplication>,
        rows: List<InstalledApplication>,
    ): LauncherListEmptiness? = when {
        rows.isNotEmpty() -> null

        data.catalogue.isEmpty() -> LauncherListEmptiness.DeviceHasNoApplications

        // Nothing is drawn because the user asked for the hidden ones and there are none.
        section == LauncherSection.Hidden && visible.isEmpty() -> LauncherListEmptiness.NothingHidden

        // Everything there is, is hidden, and no reveal is active: the drawer is legitimately empty
        // and says why rather than pretending the device is empty.
        !revealHidden && visible.isEmpty() -> LauncherListEmptiness.AllApplicationsHidden

        else -> LauncherListEmptiness.NoSearchResults
    }

    /** One successful read: the catalogue and the stored hidden set, read together. */
    private data class Loaded(
        val catalogue: List<InstalledApplication>,
        val hiddenRead: HiddenApplicationsRead,
    )

    companion object {

        /**
         * Factory that supplies the dependencies of [LauncherViewModel] from the application
         * container.
         *
         * Four dependencies, and no App Lock one among them: the launcher can show hidden
         * applications, and cannot reach the protected set even by accident.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NivaraApplication
                val container = application.container
                LauncherViewModel(
                    applicationRepository = container.applicationRepository,
                    hiddenApplicationRepository = container.hiddenApplicationRepository,
                    applicationLauncher = container.applicationLauncher,
                    sessionManager = container.sessionManager,
                )
            }
        }
    }
}
