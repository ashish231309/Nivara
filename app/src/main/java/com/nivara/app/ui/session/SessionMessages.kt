package com.nivara.app.ui.session

import androidx.annotation.StringRes
import com.nivara.app.R
import com.nivara.app.domain.security.AuthenticationSource
import com.nivara.app.domain.security.SessionState

/**
 * Maps session state to user-facing text.
 *
 * Two things are stated deliberately and nowhere else: that the session is temporary, and that it
 * is not a credential. The wording never suggests that being signed in means the application has
 * loosened anything — the primary credential is still the credential, and the session is only the
 * permission a screen needs before it shows protected content.
 */
@StringRes
fun sessionStatusRes(state: SessionState): Int = when (state) {
    is SessionState.Unauthenticated -> R.string.session_status_locked
    is SessionState.Authenticated -> R.string.session_status_authenticated
}

/** The line that says how the session was opened. */
@StringRes
fun sessionSummaryRes(state: SessionState): Int = when (state) {
    is SessionState.Unauthenticated -> R.string.session_summary_locked
    is SessionState.Authenticated -> authenticationSourceRes(state.source)
}

/** How the session was established, named in the user's terms. */
@StringRes
fun authenticationSourceRes(source: AuthenticationSource): Int = when (source) {
    AuthenticationSource.Primary -> R.string.session_source_primary
    AuthenticationSource.Biometric -> R.string.session_source_biometric
}
