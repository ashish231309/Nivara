package com.nivara.app.domain.vault

/**
 * The platform's handle for the document the user picked to import, as an opaque value.
 *
 * The domain never interprets it: on Android it is a content URI, and only the data layer turns it
 * back into something it can open. Keeping it opaque is what lets the import contract stay free of
 * `Uri`, `ContentResolver` and every other platform type — and, just as important, this reference is
 * *not* stored anywhere. It is read once, during the import, and the imported item depends on it no
 * further: the source may be moved or deleted afterwards and the vault keeps working.
 *
 * The reference is not a secret — the user can usually see the file in their own explorer — but
 * Nivara does not display it and does not log it. A content URI is long, unreadable and meaningless
 * to a person, and printing one would put a document location into whatever is reading the output.
 */
data class VaultSourceReference(val value: String) {

    init {
        require(value.isNotBlank()) { "a source reference is not blank" }
    }

    /** Redacted: the value is platform-shaped and never useful in output. */
    override fun toString(): String = "VaultSourceReference(value=REDACTED)"

    companion object {

        /** The largest reference Nivara will accept. Platform URIs are bounded in practice. */
        const val MAXIMUM_LENGTH: Int = 4 * 1024

        /**
         * A reference the platform produced, or `null` when it cannot be one.
         *
         * The door for input from outside the domain: a picker result is checked here and refused as
         * a value, so nothing has to catch an exception thrown by a screen's own input.
         */
        fun create(value: String): VaultSourceReference? =
            if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > MAXIMUM_LENGTH) {
                null
            } else {
                VaultSourceReference(value)
            }
    }
}
