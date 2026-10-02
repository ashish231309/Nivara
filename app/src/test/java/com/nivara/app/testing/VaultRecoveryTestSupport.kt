package com.nivara.app.testing

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.RecoveryStatus
import com.nivara.app.domain.vault.VaultIdentity
import com.nivara.app.domain.vault.VaultLocation
import com.nivara.app.domain.vault.VaultRecoveryFailure
import com.nivara.app.domain.vault.VaultRecoveryRepository
import com.nivara.app.domain.vault.VaultRecoverySurvey

/**
 * A recovery repository the presentation tests can steer.
 *
 * It records every call it receives and returns whatever the test decided beforehand, so the
 * screen's state machine can be exercised against every survey outcome, every refusal and a
 * successful reconnection without a real vault underneath.
 */
internal class FakeVaultRecoveryRepository(
    var surveyResult: NivaraResult<VaultRecoverySurvey> =
        NivaraResult.Success(VaultRecoverySurvey.NotAVault),
    var recoverResult: NivaraResult<VaultIdentity> =
        NivaraResult.Failure(VaultRecoveryFailure.NotAVault),
    var statusResult: NivaraResult<RecoveryStatus> =
        NivaraResult.Success(RecoveryStatus.NoVault),
) : VaultRecoveryRepository {

    /** The recovery code setup hands to the screen, when setup should succeed. */
    var setUpCode: String? = null

    /** The failure setup reports, when setup should fail. */
    var setUpFailure: Exception? = null

    val surveyed: MutableList<VaultLocation> = mutableListOf()
    val recovered: MutableList<Pair<VaultLocation, String>> = mutableListOf()
    var setUpCalls: Int = 0

    override suspend fun surveyRecovery(location: VaultLocation): NivaraResult<VaultRecoverySurvey> {
        surveyed += location
        return surveyResult
    }

    override suspend fun recover(location: VaultLocation, code: String): NivaraResult<VaultIdentity> {
        recovered += location to code
        return recoverResult
    }

    override suspend fun <T> setUpRecovery(
        presentCode: suspend (code: String) -> NivaraResult<T>,
    ): NivaraResult<T> {
        setUpCalls += 1
        setUpFailure?.let { failure -> return NivaraResult.Failure(failure) }
        val code = setUpCode
            ?: return NivaraResult.Failure(VaultRecoveryFailure.CryptographyFailed)
        return presentCode(code)
    }

    override suspend fun recoveryStatus(): NivaraResult<RecoveryStatus> = statusResult
}
