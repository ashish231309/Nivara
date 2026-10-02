package com.nivara.app.domain.permissions

import com.nivara.app.core.common.NivaraResult

/**
 * Android's overlay control for Nivara: its current state and the system screen where the user
 * changes it.
 *
 * The contract carries no Android types, so the presentation layer that gates on it can be
 * exercised without a device. Reading the state never throws: a platform failure is reported as
 * [OverlayCapability.Unavailable] rather than as an exception or as a refusal.
 *
 * Two things this contract deliberately cannot do: there is no method that grants the capability,
 * and nothing here draws anything. App Lock only needs to know whether the capability exists; the
 * component that owns the overlay window implements the drawing.
 */
interface OverlayCapabilityRepository {

    /** The current grant state. Asking never changes it. */
    suspend fun status(): OverlayCapability

    /**
     * Opens Android's overlay-permission settings for Nivara.
     *
     * The screen belongs to Android. A success only means the screen was opened — never that the
     * grant changed — and a [NivaraResult.Failure] means no settings screen could be reached, which
     * is reported rather than swallowed.
     */
    suspend fun openSettings(): NivaraResult<Unit>
}
