package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Additional requirements for a device key.
 *
 * @param userAuthenticationRequired when `true`, the platform only permits use of the key after
 *   the user has authenticated (screen lock or biometric). Nivara does not create such keys yet —
 *   biometric and lock flows belong to later stages — but the parameter is modelled now so the
 *   key-material semantics do not have to change when they arrive.
 */
data class DeviceKeyConfig(
    val userAuthenticationRequired: Boolean = false,
) {
    companion object {
        /** Default: a non-exportable AES-256-GCM key usable by the app while the device is unlocked. */
        val Default: DeviceKeyConfig = DeviceKeyConfig()
    }
}

/**
 * Keeps application keys inside the platform key store.
 *
 * Keys created here never leave the platform: only a handle ([EncryptionKey.DeviceProtected]) is
 * returned, and key material cannot be exported (`SecretKey.getEncoded()` is null for these keys).
 * This is how Nivara protects anything that must survive without a user credential — while also
 * accepting that the platform destroys the key when the user removes their screen lock, which is
 * the trade-off that makes theft of an unlocked device less useful.
 *
 * The interface is deliberately small: it never exposes a keystore class, an entry handle or a
 * raw key, so no Android Keystore type can leak into the rest of the application.
 */
interface DeviceKeyStore {

    /** `true` when a key exists under [alias]. */
    suspend fun exists(alias: String): NivaraResult<Boolean>

    /**
     * Returns the key stored under [alias], creating it if it is missing.
     *
     * If the alias already holds a key, that key is returned unchanged: Nivara never silently
     * replaces key material, because everything encrypted under the old key would become
     * unreadable. Fails with [CryptographicFailure.KeyGenerationFailed] when the platform refuses
     * generation.
     */
    suspend fun getOrCreateKey(
        alias: String,
        config: DeviceKeyConfig = DeviceKeyConfig.Default,
    ): NivaraResult<EncryptionKey>

    /**
     * Returns the key stored under [alias], or fails with [CryptographicFailure.KeyUnavailable]
     * when nothing is stored there.
     */
    suspend fun retrieveKey(alias: String): NivaraResult<EncryptionKey>

    /**
     * Reports whether the key under [alias] exists *and* can still be used.
     *
     * `false` covers both "no such key" and "the platform permanently invalidated it" (which
     * happens when the user enrols a new biometric or removes the screen lock). Callers that must
     * distinguish the two cases use [exists] and [retrieveKey]; callers that only need to know
     * whether the key is usable — for example a future re-authentication prompt — use this.
     */
    suspend fun isKeyUsable(alias: String): NivaraResult<Boolean>

    /** Deletes the key under [alias] if present. Succeeds when nothing is stored there. */
    suspend fun deleteKey(alias: String): NivaraResult<Unit>
}
