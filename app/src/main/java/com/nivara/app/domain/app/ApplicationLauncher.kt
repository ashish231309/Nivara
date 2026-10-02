package com.nivara.app.domain.app

import com.nivara.app.core.common.NivaraResult

/**
 * Opens an application by its package name.
 *
 * Launching is a platform operation, so the contract lives with the application model and the
 * implementation lives in the data layer. The alternative — resolving and starting intents from the
 * screen — would put `PackageManager` and `Intent` in the presentation layer, and a launcher that
 * built intents of its own would be one edit away from starting something the user did not select.
 *
 * ### Identity, again
 *
 * The argument is a package name, exactly as discovery reported it. There is no overload that takes
 * a label, an index into a list, or user-entered text: a launcher row carries the name the platform
 * produced, and the row's label is only what is drawn. A caller cannot ask Nivara to open "the third
 * application" or "Camera", because both would be guesses and one of them would eventually open the
 * wrong thing.
 *
 * ### What an implementation must not do
 *
 * It must not change anything about the application it opens. No enabled state, no component state,
 * no manifest, no package-manager record — launching is requested, and Android decides. It must also
 * not require any permission: Android lets an application start another application's launcher
 * activity without one.
 */
interface ApplicationLauncher {

    /**
     * Starts the launcher entry of [packageName].
     *
     * Suspending, like every other contract that reaches the platform: the call crosses into the
     * system's package manager, and a caller should be able to keep its own state honest while that
     * happens rather than blocking the thread that draws the screen.
     *
     * A [NivaraResult.Failure] carries an [ApplicationLaunchFailure] and means the application was
     * not opened. A caller must treat that as a failed action rather than reporting success, and the
     * two failure cases below tell it whether re-reading the device is worth doing.
     */
    suspend fun launch(packageName: String): NivaraResult<Unit>
}

/**
 * Why an application could not be opened.
 *
 * Two cases, because a caller acts differently on each:
 *
 * * [NotLaunchable] means the application is gone or has no launcher entry any more — the row the
 *   user tapped is stale, so re-reading the catalogue is worth doing.
 * * [LaunchRefused] means the platform refused the start for a reason that has nothing to do with
 *   the catalogue — the row is fine and there is nothing to re-read.
 *
 * The messages are fixed, non-secret strings. They carry no package name, no path and no platform
 * detail, so they stay safe if they ever reach a crash report, and they are never derived from what
 * the user tapped.
 */
sealed class ApplicationLaunchFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The application has no launcher entry that can be started, or it is no longer installed. */
    data object NotLaunchable : ApplicationLaunchFailure("the application cannot be started")

    /** The platform refused to start the application. */
    data object LaunchRefused : ApplicationLaunchFailure("the application was not started")
}
