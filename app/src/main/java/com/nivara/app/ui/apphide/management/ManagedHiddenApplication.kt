package com.nivara.app.ui.apphide.management

import com.nivara.app.domain.app.InstalledApplication
import com.nivara.app.domain.apphide.ApplicationVisibility

/**
 * Which applications the list is showing.
 *
 * Two sections and no more, matching the App Lock settings list: everything the device can launch,
 * and the applications the user has hidden. A third "visible only" section was considered and left
 * out — the all-applications list already states each row's visibility, so a section that only
 * removed rows would suggest that membership in the other section is uncertain, which it is not.
 */
enum class HiddenSection {

    /** Every discovered application, hidden or not. */
    All,

    /** Only the applications in the stored hidden set. */
    Hidden,
}

/**
 * One application in the hidden-application list: what the device has, and what Nivara says about
 * hiding it.
 *
 * The pair exists because neither half is enough. Discovery answers "what can the user open?" and
 * the stored hidden set answers "what did the user ask to keep out of sight?"; a list has to show
 * both, and it must not merge them into a single stored flag. Nothing here is persisted — the row is
 * rebuilt from the two repositories on every load and after every change.
 *
 * The visibility is deliberately **not** the App Lock protection state. An application can be
 * hidden and unprotected, protected and visible, both, or neither, and this row says nothing about
 * protection: the two dimensions are configured on two screens and neither one changes the other.
 */
data class ManagedHiddenApplication(
    val application: InstalledApplication,
    /**
     * What Nivara can say about hiding this application, or `null` when it cannot say.
     *
     * `null` is not "visible": it is the honest answer when the stored set could not be read. The
     * screen draws that as its own state, and never as a row that quietly looks visible.
     */
    val visibility: ApplicationVisibility?,
) {

    /** Shown in the row and searched by the query. Never an identity. */
    val label: String get() = application.label

    /** The identity this row acts on. */
    val packageName: String get() = application.packageName
}
