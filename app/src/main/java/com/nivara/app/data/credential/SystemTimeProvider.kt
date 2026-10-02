package com.nivara.app.data.credential

import com.nivara.app.domain.credential.TimeProvider

/**
 * The production [TimeProvider]: the device's wall clock.
 *
 * Security code is not allowed to read the clock directly — a timestamp is not a secret, and
 * using one where randomness is required is a real defect, so the rule is enforced by the
 * repository checks. Attempt throttling genuinely needs the time, so the one call to the
 * platform clock is isolated here, behind an interface the tests replace with a value they
 * control. This is the only place in the credential layer permitted to read the clock.
 */
internal class SystemTimeProvider : TimeProvider {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun toString(): String = "SystemTimeProvider"
}
