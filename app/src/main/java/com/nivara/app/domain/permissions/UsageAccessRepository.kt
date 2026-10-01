package com.nivara.app.domain.permissions

import com.nivara.app.core.common.NivaraResult

/**
 * Android's Usage Access control for Nivara: its current state and the system screen where the user
 * changes it.
 *
 * The contract carries no Android types, so the setup screen's state machine can be exercised
 * without a device. Reading the state never throws: a platform failure is reported as
 * [UsageAccessStatus.Unavailable] rather than as an exception or as a refusal.
 *
 * Two things this contract deliberately cannot do: there is no method that grants the capability,
 * and nothing here reads usage data. App Lock only needs to know whether the capability exists; the
 * stage that actually reads statistics declares its own contract.
 */
interface UsageAccessRepository {

    /** The current grant state. Asking never changes it. */
    suspend fun status(): UsageAccessStatus

    /**
     * Opens Android's Usage Access settings, where the user grants or revokes the capability.
     *
     * The screen belongs to Android. A success only means the screen was opened — never that the
     * grant changed — and a [NivaraResult.Failure] means no settings screen could be reached.
     */
    suspend fun openSettings(): NivaraResult<Unit>
}
