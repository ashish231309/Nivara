package com.nivara.app.data.applock

import android.content.Context
import com.nivara.app.data.credential.AtomicFiles
import java.io.File

/**
 * The one durable answer to "did the user leave App Lock protection on?".
 *
 * Protection itself lives in the platform components — the service, the monitor, the surface —
 * and none of those survive the device restarting. This store is what lets Nivara remember the
 * user's decision across that restart, so a boot receiver can restore protection without asking
 * again. It holds exactly one byte and nothing else: no session, no protected set, no
 * authentication material, and it is one atomic whole-file write through [AtomicFiles], like
 * every other record the application keeps.
 *
 * The runner writes it when the user turns protection on or off, and the boot receiver and the
 * activity's resume path only read it. A missing, unreadable or corrupt file is "off":
 * protection is never restarted on a guess.
 */
class ProtectionRunStateStore(context: Context) {

    private val file = File(context.filesDir, RUN_STATE_FILE)

    /** `true` when the user last left protection running. Missing or corrupt reads as `false`. */
    fun isEnabled(): Boolean = runCatching {
        AtomicFiles.readOrNull(file)?.decodeToString(Charsets.US_ASCII)?.trim() == ON
    }.getOrDefault(false)

    /** Records the user's decision. Called only by the protection runner, never inferred. */
    fun setEnabled(enabled: Boolean) {
        runCatching {
            AtomicFiles.write(file, (if (enabled) ON else OFF).encodeToByteArray())
        }
    }

    private companion object {
        const val RUN_STATE_FILE = "protection-runstate.nvr"
        const val ON = "1"
        const val OFF = "0"
    }
}
