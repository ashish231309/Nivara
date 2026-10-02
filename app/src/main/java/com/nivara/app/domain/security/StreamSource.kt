package com.nivara.app.domain.security

/**
 * Bytes handed to [EncryptionService.encryptStream] in the pieces it asks for.
 *
 * The streaming primitive reads this rather than a stream because the bytes it encrypts do not
 * arrive on a thread Nivara owns: the vault reads the document a provider opened, suspending while
 * the platform does the reading, and encryption must not turn that into a blocking read. What the
 * primitive needs is only the ability to ask for the next piece while it works.
 *
 * The contract is deliberately narrow:
 *
 * * [read] fills as much of the buffer as it can and returns how many bytes it wrote, or `-1` when
 *   there is nothing more. It never returns `0` while the source is unfinished.
 * * A source that fails half way throws its own typed failure instead of reporting `-1`, because an
 *   ended source and an unreadable one must not mean the same thing: a truncated file that still
 *   authenticated would be worse than a failed encryption.
 * * Nothing here is asked for a whole file: the caller supplies the buffer and so decides how much
 *   memory the operation can use.
 */
interface StreamSource {

    /**
     * Reads up to `buffer.size` bytes into [buffer], starting at index 0.
     *
     * @return the number of bytes read, or `-1` when the source has ended.
     */
    suspend fun read(buffer: ByteArray): Int
}
