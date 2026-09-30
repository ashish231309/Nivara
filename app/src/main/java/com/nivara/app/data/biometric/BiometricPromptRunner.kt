package com.nivara.app.data.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The text of one platform prompt, resolved before the prompt is shown. */
internal class PromptCopy(
    val title: String,
    val subtitle: String,
    val negativeButton: String,
)

/**
 * What one run of Android's prompt produced.
 *
 * [failedAttempts] counts how many times a biometric was presented and not recognised. The
 * platform keeps the prompt open after a mismatch so the user can try again with a better
 * placement, which means the mismatches are known only when the prompt finally ends.
 */
internal sealed interface PromptResult {

    /** The platform accepted the user and authorized [cipher] for exactly one operation. */
    data class Succeeded(val cipher: Cipher?, val failedAttempts: Int) : PromptResult

    /** The prompt ended with one of Android's own error codes. */
    data class Error(val errorCode: Int, val failedAttempts: Int) : PromptResult
}

/**
 * Shows Android's own biometric prompt, once.
 *
 * Nivara never draws a biometric prompt: matching, the prompt itself, retry behaviour and hardware
 * lockout all belong to the platform, and an application-drawn screen could not change any of
 * them. This class exists only to turn the platform's callback API into something a coroutine can
 * await, and to hand the Keystore cipher along as the prompt's crypto object, so that a successful
 * authentication is the only way the operation can be completed.
 *
 * The prompt is created and shown on the main thread, as the platform requires, and its callbacks
 * arrive on that same thread. Cancelling the surrounding coroutine cancels the prompt, which is
 * what happens when the user leaves the screen while it is open.
 */
internal class BiometricPromptRunner(private val activity: FragmentActivity) {

    suspend fun authenticate(cipher: Cipher, copy: PromptCopy): PromptResult =
        withContext(Dispatchers.Main) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(copy.title)
                .setSubtitle(copy.subtitle)
                .setNegativeButtonText(copy.negativeButton)
                // Strong biometrics only, and never a device credential: the crypto object is
                // authorized for one operation by one biometric match, and the primary credential
                // stays Nivara's own fallback rather than the platform's.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
                ?: return@withContext PromptResult.Error(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, 0)

            suspendCancellableCoroutine { continuation ->
                val callback = object : BiometricPrompt.AuthenticationCallback() {

                    private var failedAttempts = 0

                    override fun onAuthenticationFailed() {
                        // A biometric was presented and not recognised. The prompt stays open, so
                        // this is not the end of the attempt; it is counted and reported with the
                        // result the caller finally receives.
                        failedAttempts++
                    }

                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(
                                PromptResult.Succeeded(
                                    cipher = result.cryptoObject?.cipher ?: cipher,
                                    failedAttempts = failedAttempts,
                                ),
                            )
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // The platform's message is deliberately dropped: it is not shown, logged
                        // or stored anywhere, and the code is what Nivara maps.
                        if (continuation.isActive) {
                            continuation.resume(PromptResult.Error(errorCode, failedAttempts))
                        }
                    }
                }

                val prompt = BiometricPrompt(activity, activity.mainExecutor, callback)
                prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                continuation.invokeOnCancellation {
                    runCatching { prompt.cancelAuthentication() }
                }
            }
        }
}
