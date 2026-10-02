package com.nivara.app.data.vault

import com.nivara.app.core.common.NivaraResult
import com.nivara.app.domain.vault.VaultFailure
import com.nivara.app.domain.vault.VaultImportFailure
import com.nivara.app.domain.vault.VaultUnreadableReason
import java.io.FileNotFoundException

/**
 * How the vault's steps talk about failure among themselves.
 *
 * The import pipeline is built out of calls that each report a typed failure, and it needs one
 * vocabulary at the end: [VaultImportFailure]. These helpers are that translation, in one place, so
 * no step has to guess what another step's failure means — and so nothing platform-shaped can travel
 * upwards and be reported as a vault problem it is not.
 *
 * Nothing here swallows anything: a failure without a known shape becomes "the storage could not be
 * reached" rather than a success, which is the only safe direction for an import to be wrong in.
 */
internal class VaultSourceException(val failure: VaultImportFailure) :
    Exception(failure.message, failure)

/**
 * A step's own typed failure, carried through a call that reports storage failures.
 *
 * The write call hands the pipeline an `OutputStream`, so a cryptographic failure inside it has to
 * travel as an exception; this is that exception, and it keeps the typed failure inside it instead of
 * collapsing to "the write failed".
 */
internal class VaultImportException(val failure: VaultImportFailure) :
    Exception(failure.message, failure)

/** The import's own failure behind an exception a step threw. */
internal fun Exception?.asImportFailure(): VaultImportFailure = when (this) {
    null -> VaultImportFailure.StorageUnavailable
    is VaultImportFailure -> this
    is VaultSourceException -> failure
    is VaultImportException -> failure
    // The vault's own storage translates its failures before they travel (see `mapVaultFailure`), so
    // a plain platform exception here came from the source side, where the meaning is different.
    is SecurityException -> VaultImportFailure.SourceAccessDenied
    is FileNotFoundException -> VaultImportFailure.SourceUnavailable
    is VaultFailure -> when (this) {
        VaultFailure.AccessDenied -> VaultImportFailure.AccessDenied
        VaultFailure.KeyUnavailable -> VaultImportFailure.KeyUnavailable
        VaultFailure.CryptographyFailed -> VaultImportFailure.CryptographyFailed
        VaultFailure.WriteFailed -> VaultImportFailure.WriteFailed
        VaultFailure.VerificationFailed -> VaultImportFailure.VerificationFailed
        is VaultFailure.VaultUnreadable ->
            if (reason == VaultUnreadableReason.KeyUnavailable) {
                VaultImportFailure.KeyUnavailable
            } else {
                VaultImportFailure.StorageUnavailable
            }

        else -> VaultImportFailure.StorageUnavailable
    }

    else -> VaultImportFailure.StorageUnavailable
}

/** The same result with its failure in the import's vocabulary. */
internal fun <T> NivaraResult<T>.mapToImportFailure(): NivaraResult<T> = when (this) {
    is NivaraResult.Success -> this
    is NivaraResult.Failure -> NivaraResult.Failure(error.asImportFailure())
}

/** The import failure behind a failed result. */
internal fun <T> NivaraResult<T>.importFailureOf(): VaultImportFailure = when (this) {
    is NivaraResult.Success -> VaultImportFailure.StorageUnavailable
    is NivaraResult.Failure -> error.asImportFailure()
}

/**
 * The exception that carries a failed encryption out of a write block.
 *
 * A source that cannot be read has its own case; anything else the encryption service refuses is a
 * cryptographic failure of the kind the service already names.
 */
internal fun <T> NivaraResult<T>.encryptionException(): Exception = when (this) {
    is NivaraResult.Success -> VaultImportException(VaultImportFailure.CryptographyFailed)
    is NivaraResult.Failure -> when (val error = error) {
        is VaultImportFailure -> VaultImportException(error)
        is VaultSourceException, is VaultImportException -> error
        else -> VaultImportException(VaultImportFailure.CryptographyFailed)
    }
}
