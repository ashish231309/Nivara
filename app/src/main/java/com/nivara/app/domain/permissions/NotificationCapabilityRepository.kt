package com.nivara.app.domain.permissions

/**
 * The notification permission for Nivara: only its current state.
 *
 * Unlike every other capability in this package, this one has a runtime dialog, and the dialog
 * belongs to the screen, not to a repository: the contract therefore only reports the state,
 * and the presentation layer asks for the permission through the platform's own activity
 * result contract. Reading the state never throws: a platform failure is reported as
 * [NotificationCapability.Unavailable] rather than as an exception or a refusal.
 */
interface NotificationCapabilityRepository {

    /** The current permission state. Asking never changes it. */
    suspend fun status(): NotificationCapability
}
