# Biometric unlock: Android's prompt as a secondary path

This document is the design record for the biometric layer added in this work. It explains what
Nivara does, what it deliberately delegates to Android, what it stores, how it behaves when the
platform takes the key away, and what it can never do.

Scope: biometric authentication as a **secondary** convenience beside the primary credential.
Biometrics do not unlock anything by themselves in this work — sessions, timeouts and app locking
come later and consume the result. Nothing here replaces the PIN, password or pattern.

## The rule that shapes everything

**Android owns biometric security; Nivara only decides when to ask and what to do with the
answer.** Matching, template storage, the sensor state, the prompt UI and the hardware lockout are
the platform's. Nivara never implements fingerprint code, never touches biometric hardware, never
draws a prompt and never stores a fingerprint, a face, a template or a raw key.

The second rule: **the primary credential stays authoritative.** No value of the biometric state
means "unlocked". Nothing here can be turned on without a configured credential, nothing here can
replace one, and every failure mode ends at the same place — the credential the user enrolled.

## Types

| Type | Meaning |
| --- | --- |
| `BiometricUnavailability` | `NoHardware`, `HardwareUnavailable`, `NotEnrolled`, `Unsupported`, `SecurityUpdateRequired`, `Unknown` — the platform's own reasons, translated one for one. |
| `BiometricStatus` | `Unavailable(reason)`, `Disabled`, `Enabled`, `Invalidated`. What Nivara's configuration is, given the stored record, the key and the hardware. |
| `BiometricState` | The status plus `retryAfterMillis`, Nivara's own delay. Never a description of Android's lockout. |
| `BiometricAuthenticationOutcome` | `Succeeded`, `Failed(attemptsRemaining, blockedForMillis)`, `Cancelled`, `TemporarilyBlocked(retryAfterMillis)`, `SystemBlocked(permanent)`, `NotEnabled`, `Invalidated`, `Unavailable(reason)`. The boundary a later session manager consumes. |
| `BiometricFailure` | Typed failures of *enabling* and *disabling*: no primary credential, unavailable, cancelled, not enabled, key store unavailable, key generation failed, storage unavailable, the platform's lockout. |

`BiometricStatus.resolve(configured, keyUsable, unavailability)` is a pure function, so the whole
truth table is unit-tested without a device. Its order matters:

1. hardware the platform will never support (`NoHardware`, `Unsupported`,
   `SecurityUpdateRequired`) is reported as unavailability, because no stored configuration can
   change it;
2. otherwise a stored configuration whose key cannot authenticate is `Invalidated`;
3. a stored configuration on a device whose enrolment has been removed is `Invalidated` too — the
   key is dead, and only turning biometric unlock on again can replace it;
4. otherwise the configuration decides between `Enabled`, `Disabled` and `Unavailable(reason)`.

A sensor that is merely busy leaves a configured device `Enabled`: that is a condition of the
prompt, not a problem with the configuration.

## The Keystore key

| Concern | Implementation |
| --- | --- |
| Alias | `nivara.biometric.v1` (versioned, in the platform key store, never in application storage) |
| Algorithm | AES-256-GCM, `AES/GCM/NoPadding`, 128-bit tag |
| Authorisation | `setUserAuthenticationRequired(true)` for **every use**: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+, `setUserAuthenticationValidityDurationSeconds(-1)` below it |
| Enrolment changes | `setInvalidatedByBiometricEnrollment(true)` — enrolling a new biometric destroys the key |
| Exportability | None. The key never leaves the platform; `getEncoded()` is unavailable for it |
| Public key material | None. Nivara cannot verify anything with it, and does not try |

The key protects exactly one thing: a 32-byte random token that Nivara stores **encrypted**. The
token is not used as a key and nothing is derived from it. It exists so that a successful
authentication has a cryptographic consequence — the cipher carried by the prompt's `CryptoObject`
can only complete after Android has accepted the user — and so that an invalidated key is detected
before the user is asked for anything.

### What is written to disk

One small file, `filesDir/security/biometric.token`, holding the non-secret half of the
configuration:

| offset | size | field |
| --- | --- | --- |
| 0 | 4 | magic `NVBT` |
| 4 | 1 | format version (1) |
| 5 | 1 | IV length (12) |
| 6 | 12 | IV of the one GCM operation that produced the record |
| 18 | 1 | ciphertext length (48) |
| 19 | 48 | the encrypted token (32 bytes + 16-byte GCM tag) |
| 67 | 4 | CRC-32 of everything before it, big-endian |

71 bytes, written whole and replaced atomically (temporary file, `fsync`, rename) through the same
storage helpers the credential record uses. The format is strict: a different magic, version,
length field or checksum is rejected rather than guessed at. A missing or damaged file reads as
"not enabled", never as a hard error, so the user is never stuck with a state nothing can clear.

The biometric failure counter lives in its own file, `filesDir/security/biometric-attempts.nva`,
in the same `NVAT` format the credential's counter uses — and it is a different file on purpose.

### Setup and teardown

- **Enable.** Requires a configured primary credential (checked in the domain layer, not only in
  the UI), a device the platform reports as able to authenticate, and a foreground host for the
  prompt. Nivara removes any leftover key and record, creates a fresh key, asks Android to
  authenticate once with the encryption cipher as the crypto object, and only then writes the
  record. Cancelling or failing deletes the key again: there is no half-configured state.
- **Authenticate.** Prefers the stored IV and ciphertext, prepares a decryption cipher, shows
  Android's prompt with it, decrypts the token after the platform reports success, clears the
  plaintext immediately and forgets the failure counter.
- **Disable.** Removes the record and the key. It needs a successful authentication first when the
  key can still authenticate anything; a configuration whose key the platform already invalidated,
  or one on hardware that cannot authenticate at all, is simply disposed of, because removing it
  takes no capability away and no prompt could ever succeed. The primary credential is untouched in
  either case.

Both enable and disable are carried out only after the user has verified the primary credential on
the biometric screen. There is no background component, no receiver and no service that could reach
them, and no path to them from an unauthenticated state.

## Enrolment changes and invalidation

When the device's biometric enrolment changes, the platform invalidates the key. Nivara's response
is deliberately boring:

- the stored configuration is reported as `Invalidated`; nothing is recreated silently;
- the primary credential is not touched, and remains the way in;
- an explicit "turn biometric unlock on again" replaces the key and the record;
- no user is ever locked out of their own data because a fingerprint was added.

## The application's failure policy

| Rule | Value |
| --- | --- |
| Free failures | 5 |
| Delay once they are used up | 30 seconds, for every further attempt |
| Reset | A successful authentication; and a successful primary-credential authentication clears the delay |
| Growth | None. This is Nivara's own fixed interval, not an exponential backoff |
| Permanence | None. The user waits; they are never locked out |

Nivara's counter and Android's lockout are different mechanisms and are reported differently:

- Nivara's delay is checked **before** the prompt, so a throttled path cannot reach the sensor.
  It is shown as `TemporarilyBlocked(retryAfterMillis)` and described in the UI as Nivara's own
  pause.
- Android's lockout arrives as a prompt error (`ERROR_LOCKOUT`, `ERROR_LOCKOUT_PERMANENT`). It is
  reported as `SystemBlocked(permanent)` and never as a Nivara delay: Nivara cannot clear it,
  cannot shorten it and does not claim otherwise. Only the platform clears it.

Biometric failures are never recorded against the credential. The two counters are separate files,
separate policies and separate types, and a unit test asserts the separation with the real
implementations.

## Where the code lives

```
domain/security/BiometricAuthenticator.kt     The contract; Android-free
domain/security/BiometricState.kt             Status, state, outcomes and the resolve() rule
domain/security/BiometricFailure.kt           Typed failures of enable/disable
domain/security/BiometricThrottlePolicy.kt    The 5-then-30-seconds rule
data/biometric/AndroidBiometricAuthenticator.kt   The implementation; prompt + key orchestration
data/biometric/BiometricPromptRunner.kt       Android's prompt, awaited from a coroutine
data/biometric/BiometricPromptMapping.kt      Pure translations of platform codes
data/biometric/BiometricTokenCodec.kt         The NVBT record format
data/biometric/BiometricTokenStore.kt         Atomic storage of that record
data/security/AndroidBiometricKeyStore.kt     Key creation, deletion and invalidation detection
ui/biometric/                                 Settings screen, view model and message mapping
```

The prompt needs a foreground activity, which the domain layer cannot name. `MainActivity`
implements the `BiometricPromptHost` marker and registers itself while it is alive; only the data
implementation looks at the value, and it expects the platform activity. Nivara never draws a
biometric prompt.

## Merged manifest

AndroidX Biometric declares `android.permission.USE_BIOMETRIC` (and the deprecated
`USE_FINGERPRINT`) in its own manifest, along with a non-exported internal dialog activity. Those
merge into the application, which is why Nivara's own manifest still declares no permissions and
why nothing in the application requests one at runtime.

The library also brings `androidx.fragment` into the build, which is what lets `MainActivity` be a
`FragmentActivity` — the type Android's prompt is constructed from. Its compat dialog (and the
`appcompat` it depends on) is only reached below API 28; Nivara starts at API 28, where the
platform's own prompt is used.

## What is deliberately not here

- No custom biometric prompt, no fingerprint API, no direct hardware access.
- No biometric data of any kind: no templates, no images, no per-finger state.
- No raw key material in application storage, and no key material in logs.
- No bypass of Android's lockout, and no claim that one exists.
- No session, no timeout, no app locking: those are later work and consume
  `BiometricAuthenticationOutcome` rather than re-implementing it.

## Verification status

What is verified automatically:

- the status rule, the throttle policy, the record format, the platform-code mappings and the
  removal rule are unit-tested on the JVM with the real production code;
- the failure counters are tested through the real tracker and the real file store;
- the screen's state machine is tested against the domain interfaces.

The instrumented suite in `app/src/androidTest` adds what only a device can answer: that the
device's own capability report is translated without inventing a reason, that Nivara's status never
contradicts it, that biometric unlock cannot be turned on without a primary credential, and that
authenticating or disabling something that was never configured is refused rather than faked. Every
case is chosen so the authenticator answers *before* it would show a prompt.

What is **not** verified in this environment:

- the prompt itself, key generation, invalidation after an enrolment change, and the `CryptoObject`
  flow. These need a real device or emulator with an enrolled biometric and a person to present
  one, so no automated test drives them: the instrumented tests are compiled but were never
  executed here, and no JVM test claims to stand in for them. A full pass remains manual, and the
  outcome of that manual pass is not claimed anywhere in this repository.
