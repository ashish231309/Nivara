package com.nivara.app.data.permissions

import com.nivara.app.domain.permissions.UsageAccessStatus

/**
 * The application-operation modes that decide Usage Access, mirrored from Android's
 * `AppOpsManager`.
 *
 * They are repeated here as plain integers so the translation below can be tested on the JVM,
 * without an Android framework. The mirror is not taken on trust: an instrumented test asserts
 * these values against `AppOpsManager` itself on a real device.
 */
internal const val USAGE_ACCESS_MODE_ALLOWED = 0

/** `AppOpsManager.MODE_ERRORED`: the operation cannot be performed, which is not a refusal. */
internal const val USAGE_ACCESS_MODE_ERRORED = 2

/**
 * Translates an application-operation mode into the domain's answer.
 *
 * Only "allowed" is a grant. Every other mode — ignored, default, or one this build does not know —
 * is reported as not granted, because Android's default for an unset operation is not to allow it.
 * The error mode is reported as unavailable instead of as a refusal: in that state the capability
 * cannot be used or changed, and saying "not granted" would hide a broken check behind a normal
 * answer.
 */
internal fun usageAccessStatusForMode(mode: Int): UsageAccessStatus = when (mode) {
    USAGE_ACCESS_MODE_ALLOWED -> UsageAccessStatus.Granted
    USAGE_ACCESS_MODE_ERRORED -> UsageAccessStatus.Unavailable
    else -> UsageAccessStatus.NotGranted
}
