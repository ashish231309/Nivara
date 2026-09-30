package com.nivara.app.domain.security

/**
 * A key that can be used for authenticated encryption or key wrapping.
 *
 * Two kinds exist, and the difference matters for how the key is protected at rest:
 *
 * - [InProcess] — the bytes are held by this process. This is unavoidable for the keys Nivara
 *   generates itself (vault keys, recovery keys, keys derived from a credential) at the moment
 *   they are used, but it means the key exists in memory while it is in use.
 * - [DeviceProtected] — the bytes never enter this process; the key lives in the Android
 *   Keystore and operations are performed there, hardware-backed on devices that support it.
 *
 * Instances deliberately do not expose key material through `toString`, `equals` or `hashCode`,
 * and the type is closed, so there is no third kind that weakens the model.
 */
sealed interface EncryptionKey {

    /** Key size in bits. Nivara uses 256-bit keys. */
    val keySizeBits: Int

    /** `true` when the key material stays inside the platform key store. */
    val isDeviceProtected: Boolean

    /** Non-secret label for diagnostics, for example `keystore:nivara.vault` or `in-process:vault-key`. */
    val label: String

    /**
     * A key whose material is held by the process, for example a vault key unwrapped for the
     * current session or a key derived from a credential.
     */
    class InProcess internal constructor(
        internal val material: SensitiveBytes,
        override val label: String,
    ) : EncryptionKey {

        override val keySizeBits: Int get() = material.size * BITS_PER_BYTE

        override val isDeviceProtected: Boolean get() = false

        /**
         * Overwrites the key material.
         *
         * Call this when a session ends, a lock engages, or an operation is finished with the
         * key. See [SensitiveBytes] for the honest limits of erasure on a managed runtime.
         */
        fun clear() {
            material.clear()
        }

        override fun toString(): String =
            "EncryptionKey.InProcess(label=$label, keySizeBits=$keySizeBits, material=REDACTED)"

        private companion object {
            const val BITS_PER_BYTE = 8
        }
    }

    /**
     * A key that lives in the Android Keystore under [alias] and performs operations there.
     *
     * Note that the key is automatically gone when the user removes their screen lock (the
     * platform deletes keys it can no longer protect), which is why callers must be prepared for
     * [CryptographicFailure.KeyUnavailable] and [CryptographicFailure.KeyInvalidated] at any time.
     */
    class DeviceProtected internal constructor(
        internal val alias: String,
        override val keySizeBits: Int = DEFAULT_KEY_SIZE_BITS,
    ) : EncryptionKey {

        override val isDeviceProtected: Boolean get() = true

        override val label: String get() = "keystore:$alias"

        override fun toString(): String =
            "EncryptionKey.DeviceProtected(alias=$alias, keySizeBits=$keySizeBits)"

        internal companion object {
            const val DEFAULT_KEY_SIZE_BITS: Int = 256
        }
    }

    companion object {
        /**
         * Wraps raw key material.
         *
         * Only for material that was just generated or derived here. Prefer passing a keystore
         * alias for anything that should not exist in memory.
         */
        fun fromRawBytes(material: SensitiveBytes, label: String = "in-process"): EncryptionKey =
            InProcess(material = material, label = label)
    }
}
