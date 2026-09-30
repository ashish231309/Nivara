package com.nivara.app.domain.security

import com.nivara.app.core.common.NivaraResult

/**
 * Read-only view of the device's current security posture.
 *
 * This contract deliberately does not authenticate anything: implementations never show a
 * prompt, never verify a credential and never touch keys. They only report settings that
 * already exist on the device, so screens can adapt to them.
 *
 * Credential verification, biometric authentication and key management are separate concerns
 * and are introduced by the stages that implement them, behind their own interfaces.
 */
interface DeviceSecurityProvider {

    /**
     * Reports whether a screen lock (PIN, password, pattern or biometric) is configured on
     * this device. Returns [NivaraResult.Failure] when the platform cannot be queried.
     */
    suspend fun isDeviceLockConfigured(): NivaraResult<Boolean>
}
