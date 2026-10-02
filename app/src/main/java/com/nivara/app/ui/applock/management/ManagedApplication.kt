package com.nivara.app.ui.applock.management

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.applock.ApplicationProtectionState

/**
 * Which applications the list is showing.
 *
 * Two sections and no more. "Protected" exists because it is the only other question that has a
 * real product purpose — what am I protecting? — and there is deliberately no "recent", "suggested"
 * or "frequently used" section: Nivara keeps no usage history, and a section that needed one would
 * be a reason to start collecting it.
 */
enum class ApplicationSection {

    /** Every discovered application, protected or not. */
    All,

    /** Only the applications in the stored protected set. */
    Protected,
}

/**
 * One application in the management list: what the device has, and what Nivara currently says about
 * protecting it.
 *
 * The pair exists because neither half is enough. Discovery answers "what can the user open?" and
 * the protected set answers "what did the user ask for?"; a list has to show both, and it must not
 * merge them into a single stored flag. Nothing here is persisted — the row is rebuilt from the two
 * repositories on every load and after every change.
 */
data class ManagedApplication(
    val application: InstalledApplication,
    /**
     * What Nivara can say about protecting this application, or `null` when it cannot say.
     *
     * `null` is not "not protected": it is the honest answer when the stored set could not be read.
     * The screen draws that as its own state, and never as an unticked box.
     */
    val state: ApplicationProtectionState?,
) {

    /** Shown in the row and searched by the query. Never an identity. */
    val label: String get() = application.label

    /** The identity this row acts on. */
    val packageName: String get() = application.packageName
}

/**
 * The two answers a management list is drawn from, read together because neither is authoritative
 * alone.
 *
 * An unreadable [protected] is **not** an empty set: it means Nivara cannot say what is protected,
 * and the list must refuse to claim anything rather than show every application as unprotected —
 * the failure the storage layer is deliberately built to fail closed against.
 *
 * [requirementsSatisfied] answers a different question: whether App Lock could actually present a
 * requirement right now, which is whether the device's two capabilities are granted. It is a
 * boolean on purpose — the rows only need "presentable or not", and *which* prerequisite is missing
 * is stated in words by the screen that owns that explanation, from the same aggregate the
 * preparation screen already reports. An unreadable capability is therefore `false`, never a silent
 * claim that protection is working. This snapshot says nothing about whether protection is
 * *running*; that is the header's own line, read from the component that owns protection.
 *
 * Both members are answers the caller obtained; this type performs no I/O and holds no cache.
 */
data class ProtectionSnapshot(
    val protected: ProtectedSetRead,
    val requirementsSatisfied: Boolean,
) {

    /** `true` when the stored set could not be read, so no row may claim anything. */
    val storedSetUnreadable: Boolean get() = protected is ProtectedSetRead.Unreadable

    /**
     * The list state for [application], or `null` when nothing may be claimed.
     *
     * `null` is returned exactly when the protected set could not be read: with no authoritative
     * answer, a row can be drawn neither as protected nor as unprotected.
     */
    fun stateOf(application: InstalledApplication): ApplicationProtectionState? =
        when (val stored = protected) {
            // No authoritative answer about the stored set, so no row in the list may claim to be
            // protected or unprotected.
            ProtectedSetRead.Unreadable -> null

            is ProtectedSetRead.Readable -> when {
                application.packageName !in stored.packageNames -> ApplicationProtectionState.NotProtected

                // What the user asked for is stored. Whether it can be presented is the
                // capabilities' answer, not the row's assumption.
                requirementsSatisfied -> ApplicationProtectionState.Protected

                else -> ApplicationProtectionState.ProtectedButUnavailable
            }
        }
}

/**
 * The stored protected set, as the screen was able to read it.
 *
 * The two cases are the whole reason this type exists: a failure to read the configuration is a
 * failure, and the list must not turn it into "nothing is protected".
 */
sealed interface ProtectedSetRead {

    /** The stored set was read. [packageNames] is what it held — possibly nothing. */
    data class Readable(val packageNames: Set<String>) : ProtectedSetRead

    /** The stored set could not be read. Nothing may be claimed about any application. */
    data object Unreadable : ProtectedSetRead
}

