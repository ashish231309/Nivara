package com.nivara.app.domain.permissions

import com.nivara.app.core.common.NivaraResult

/**
 * Android's battery-optimization exemption for Nivara: its current state and the platform
 * surfaces where the user changes it.
 *
 * The contract carries no Android types, so the onboarding state machine can be exercised
 * without a device. Reading the state never throws: a platform failure is reported as
 * [BatteryOptimizationStatus.Unavailable] rather than as an exception or a refusal.
 *
 * Unlike Usage Access and overlay, this capability has a system dialog of its own:
 * [requestExemption] asks Android to show its "ignore battery optimizations" confirmation for
 * Nivara. A success only means the dialog (or its settings fallback) was shown — never that the
 * exemption changed — and the state is read again afterwards.
 */
interface BatteryOptimizationRepository {

    /** The current exemption state. Asking never changes it. */
    suspend fun status(): BatteryOptimizationStatus

    /**
     * Shows Android's battery-exemption confirmation for Nivara, falling back to the battery
     * settings screen on devices without the dialog.
     *
     * A success only means a platform surface was shown — never that the exemption changed — and
     * a [NivaraResult.Failure] means no surface could be reached, which is reported rather than
     * swallowed.
     */
    suspend fun requestExemption(): NivaraResult<Unit>
}
