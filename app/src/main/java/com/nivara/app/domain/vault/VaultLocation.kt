package com.nivara.app.domain.vault

/**
 * Where the vault root is, as the platform describes it.
 *
 * The domain never interprets [reference]: it is the platform's own opaque handle for the folder the
 * user selected — on Android, a document-tree URI — and only the data layer turns it back into
 * something it can open. Keeping it opaque is what lets the vault contracts stay free of `Uri`,
 * `DocumentFile`, `File`, `Context` and every other platform type, and it is also what keeps the
 * location out of the domain's reasoning: a vault is identified by its
 * [VaultIdentity][com.nivara.app.domain.vault.VaultState.Ready.identity], never by where it happens
 * to sit.
 *
 * The reference is not a secret — it names a folder the user chose, usually one they can see in their
 * own file manager — but Nivara still does not display it and does not log it. A platform URI is
 * long, unreadable and meaningless to a person, and printing one would put a filesystem location into
 * whatever is looking at the output without helping anybody.
 */
data class VaultLocation(val reference: String) {

    init {
        require(reference.isNotBlank()) { "a vault location reference is not blank" }
    }

    /** Redacted: the value is platform-shaped and never useful in output. */
    override fun toString(): String = "VaultLocation(reference=REDACTED)"

    companion object {

        /**
         * The largest reference Nivara will store or accept.
         *
         * Platform URIs are bounded in practice, and a stored record larger than this is damaged
         * rather than legitimate, so accepting it would only mean carrying around whatever was
         * written there. The bound is on the encoded bytes, because that is what a record holds.
         */
        const val MAXIMUM_REFERENCE_LENGTH: Int = 4 * 1024

        /**
         * A location for a reference the platform produced, or `null` when it cannot be one.
         *
         * This is the door for input that comes from outside the domain: a selection the user made in
         * a platform picker is checked here and refused as a value, so nothing has to catch an
         * exception thrown by a screen's own input. A blank or absurd reference is not a location, and
         * the caller reports it the same way it reports a selection that could not be adopted.
         */
        fun create(reference: String): VaultLocation? =
            if (reference.isBlank() ||
                reference.toByteArray(Charsets.UTF_8).size > MAXIMUM_REFERENCE_LENGTH
            ) {
                null
            } else {
                VaultLocation(reference)
            }
    }
}

/**
 * The outcome of reading the stored vault location.
 *
 * Three cases rather than a nullable value, for the same reason the vault states are separate: a
 * damaged record must never be reported as "no vault configured", because that would invite the user
 * to pick a new folder and abandon a vault that still exists.
 */
sealed interface VaultLocationRead {

    /** A location is stored. It has not been opened or checked yet. */
    data class Present(val location: VaultLocation) : VaultLocationRead

    /** Nothing is stored: no vault has been configured on this device. */
    data object None : VaultLocationRead

    /**
     * A record exists and cannot be read.
     *
     * Nivara does not know which folder it was pointed at, so it says exactly that. It never clears
     * the record — the damaged bytes are the only remaining hint of where the vault is — and it never
     * substitutes another location.
     */
    data object Unreadable : VaultLocationRead
}
