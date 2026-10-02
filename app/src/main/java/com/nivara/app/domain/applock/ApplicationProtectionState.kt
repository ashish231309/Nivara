package com.nivara.app.domain.applock

/**
 * What an application's protection currently says in the management list.
 *
 * Three values rather than a boolean, because the question a row answers is not "is this name in
 * the stored set?" but "is this application actually protected right now?". A user who protected an
 * application and then revoked Android's overlay permission has done everything Nivara asked and is
 * still not protected, and a row that showed a plain tick would be the one thing this feature must
 * never do: claim protection that is not there.
 *
 * The state is *derived* from two independent answers every time the list is built — the stored set,
 * and whether the requirements for presenting a requirement are in place. Nothing here is stored,
 * and nothing here is a second source of truth for the protected set.
 */
enum class ApplicationProtectionState {

    /** The application is not in the stored set. Nothing about it is configured, and nothing claims otherwise. */
    NotProtected,

    /** The application is in the stored set, and every requirement for protection is satisfied. */
    Protected,

    /**
     * The application is in the stored set, and protection cannot be presented.
     *
     * The stored configuration is exactly what the user asked for; what is missing is a capability
     * or a readable protected set. The row states both, so the situation is visible instead of
     * looking like a silent failure.
     */
    ProtectedButUnavailable,
}
