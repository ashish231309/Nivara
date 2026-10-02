package com.nivara.app.domain.app

import com.nivara.app.core.common.NivaraResult

/**
 * The applications on this device that the user can open.
 *
 * Discovery is deliberately narrow. It answers one question — "what can the user launch?" — which
 * is the same set the device's launcher shows. It does not enumerate packages with no launcher
 * entry point, it reports nothing about what an application does or contains, and it returns the
 * list in [defaultApplicationOrder] so that two queries over an unchanged device produce the same
 * sequence.
 *
 * A [NivaraResult.Failure] means the platform could not be queried at all. That is reported as its
 * own outcome and is never folded into an empty list, because "no applications" and "no answer"
 * are different facts about the device.
 */
interface ApplicationRepository {

    /**
     * Every user-launchable application, ordered by label and then by package name.
     *
     * An individual application whose label cannot be resolved, or that disappears while the list
     * is being built, is skipped rather than failing the whole query; only the query itself
     * failing produces a [NivaraResult.Failure].
     */
    suspend fun installedApplications(): NivaraResult<List<InstalledApplication>>
}
