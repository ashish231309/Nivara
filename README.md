# Nivara

Nivara is a native Android application for privacy and security. It is built to keep a
person's private space — locked apps, hidden apps, and encrypted files — on their own device,
under their own control.

The project is developed in the open and in small, reviewable increments. Everything that
ships is written for the platform: no cross-platform wrappers, no unnecessary third-party UI
frameworks, and no dependencies that are not justified by code that exists.

## Project status

The repository contains the **foundation release**, the **cryptographic core**, **primary
credential enrolment**, **biometric unlock as a secondary path** and the **session layer**.

What works today:

- the application shell — home and about screens, navigation, Material 3 light/dark theme;
- reporting the device's screen-lock status;
- enrolling, verifying and changing one primary credential — a PIN, a password or a pattern —
  with a documented storage format and attempt throttling;
- biometric unlock through Android's own `BiometricPrompt`, gated by a Keystore key that requires
  a strong biometric for every use, with its own failure policy and explicit handling of an
  invalidated key;
- one in-memory authentication session, established by a primary-credential success or a
  biometric success, expiring on a fixed timeout and ended immediately by Quick Lock;
- launcher-application discovery, the Usage Access capability and the preparation screen that
  reports what App Lock needs;
- App Lock detection: which application is in the foreground, whether the protected set requires
  authentication for it, and a published requirement for the layer that will present the prompt;
- the cryptographic layer that later features are built on: AES-256-GCM authenticated encryption
  with a versioned envelope format, secure randomness, Android Keystore key management, key
  wrapping, PBKDF2 credential derivation and the recovery-key foundation.

Biometric unlock never replaces the primary credential and never unlocks anything by itself, and
the session is not one either: it is an authorization state held only while the process lives, and
it holds no credential, no key and nothing on disk. Detection decides and publishes; it draws
nothing, blocks nothing and authenticates nobody. There is still no authentication prompt over
another application, no vault, no hidden apps and no recovery flow — those are the stages that
follow, and they consume the layers
described in [`docs/crypto/README.md`](docs/crypto/README.md),
[`docs/credential/README.md`](docs/credential/README.md),
[`docs/biometric/README.md`](docs/biometric/README.md),
[`docs/session/README.md`](docs/session/README.md) and
[`docs/applock/README.md`](docs/applock/README.md).

## Planned capabilities

These are the areas Nivara is being built for. They are listed here so the direction of the
project is clear; each one is implemented in its own release and is not present yet.

- Authentication and biometric unlock
- App locking and app hiding
- An optional home-screen (launcher) experience
- An encrypted file vault, including media, albums and a recycle bin
- Recovery and session management
- Security settings, themes and the final visual design

## Technology stack

| Area | Choice |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose with Material 3 |
| Platform | AndroidX, single `:app` module |
| Concurrency | Kotlin Coroutines and Flow |
| Build | Gradle with Kotlin DSL and a Gradle version catalog |
| Minimum Android version | Android 9 (API 28) |
| Compile / target SDK | API 36 |
| JVM target | Java 17 |

Dependency versions are declared in one place: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project structure

```
app/src/main/java/com/nivara/app/
├── NivaraApplication.kt        Application entry point; owns the dependency container
├── MainActivity.kt             The single activity; hosts the Compose UI
├── di/                         Hand-written composition root (AppContainer)
├── core/common/                Types shared across layers (NivaraResult)
├── domain/                     Contracts the app depends on (no Android types)
│   ├── app/                    Installed-application model, ordering and search rule
│   ├── permissions/            Usage Access state and the App Lock setup aggregate
│   └── applock/                Protected set, foreground detection, decision rule
├── data/                       Platform-backed implementations of those contracts
│   ├── app/                    Launcher-entry discovery through the package manager
│   ├── applock/                Usage-event detector, protected set store, monitor, service
│   ├── credential/             Credential record, counters and verifier
│   ├── biometric/              Android's prompt, the NVBT record and its store
│   ├── permissions/            Usage Access app-op check and its settings entry point
│   ├── session/                The in-memory session manager
│   └── security/               JCA, Android Keystore and device state
└── ui/                         Compose UI
    ├── NivaraApp.kt            Root composable: app bar + navigation host
    ├── theme/                  Material 3 colour scheme and typography
    ├── navigation/             Destinations and the navigation graph
    ├── components/             Reusable loading and error states
    ├── home/                   Home screen, state and view model
    ├── about/                  About screen
    ├── credential/             Enrolment, verification and change screens
    ├── biometric/              Biometric settings, state and view model
    ├── applock/                App Lock preparation screen, state and view model
    └── session/                Session text shared by the screens that show it
```

The layering is deliberately small: `ui` depends on `domain` contracts, `domain` has no
Android dependencies, and `data` provides the platform implementations that `di` wires
together. Nothing is registered that is not used, so the structure can be trusted while it
grows.

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools 36 (Android Studio installs both)
- No local configuration is required beyond the Android SDK; `local.properties` is generated
  by Android Studio and is not committed.

## Building

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (R8 shrinking and resource shrinking enabled)
./gradlew :app:assembleRelease

# Install on a connected device or emulator
./gradlew :app:installDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Release builds are not signed from this repository. Signing material is supplied at build
time — for example with a `keystore.properties` file or CI secrets — and keystores, passwords
and `local.properties` are excluded by `.gitignore`.

## Testing

```bash
# Unit tests (JVM, no device required)
./gradlew :app:testDebugUnitTest

# Instrumented tests (device or emulator required)
./gradlew :app:connectedDebugAndroidTest

# Lint
./gradlew :app:lintDebug
```

Unit tests cover the pieces that carry logic but no Android dependency: the result wrapper between
layers, the home screen state machine, the navigation route table, the cryptographic layer —
envelope parsing and rejection cases, tamper detection, purpose binding, known-answer vectors from
RFC 5869 (HKDF) and PBKDF2, randomness lengths, secret-buffer handling, key wrapping and the
recovery foundation — and the credential layer, which is tested end to end with real PBKDF2 and a
controlled clock: policy rules, pattern canonicalisation, the on-disk record and counter formats,
persistence across a fresh store, enrolment, verification, change in every direction, attempt
throttling and scans proving that no credential or derived key is ever written. The biometric layer
is covered the same way: the status rule, the failure policy, the record format, the translations
of Android's own result codes, the rule that decides when removal needs another authentication, and
the screen's state machine — including the separation between Nivara's delay and Android's lockout.

The session layer is covered the same way: establishment from a success and from nothing else,
validity either side of the expiry boundary, the timer closing the gate on its own, Quick Lock's
immediacy and idempotency, and the separation between the session and both throttling counters.

The App Lock preparation layer is covered the same way: the application model and its
package-name identity rule, the default ordering and its tie-breaker, the search matching rule,
the aggregation of discovery and Usage Access into a setup state, the mapping from an
application-operation mode to a permission state, and the preparation screen's state machine —
including that a successful "open settings" is never treated as a grant.

App Lock detection is covered the same way: the protected-application identity rule, the stored
format and its atomic replacement (against real files, including a damaged one that must be
reported rather than read as "nothing is protected"), the mapping from platform event types to
foreground transitions and the fold that resolves them in time order, the protection decision in
every combination of foreground application, protected set and session, and the monitoring loop —
one loop however often it is started, a stopped run that cannot publish, a missing prerequisite
that is never mistaken for "nothing to protect", a requirement raised once per entry rather than
once per observation, and expiry and Quick Lock taking effect through the session gate alone. The
detection policy's interval and window are asserted too, so changing them is a deliberate edit.

Instrumented tests cover what only a device can prove: Android Keystore key generation,
non-exportability, invalidation detection and use through a cipher, and that the biometric key
refuses to produce output until Android has authorised a single operation. They are never simulated
on the JVM, and the flows that need a person to present a biometric remain manual.

## Security defaults

Security behaviour is built into the project's defaults rather than added at the end:

- **One permission, justified.** The manifest declares `PACKAGE_USAGE_STATS`, which makes Nivara
  visible in Android's Usage Access list and lets App Lock read usage statistics; the user grants
  it in Android's own settings and Nivara never requests it at runtime. Package visibility is
  extended with a single `<queries>` launcher-intent signature rather than `QUERY_ALL_PACKAGES`.
  Every permission has to be justified by a feature that exists; the reasons are recorded in
  [`docs/applock/README.md`](docs/applock/README.md).
- **Detection without privileges it does not need.** The App Lock service is a plain, unexported
  started service: no foreground service, no notification, no accessibility service, and no overlay
  or battery-exemption permission. Detection reads usage events and never aggregate usage history,
  keeps no record of what was used, and reports an unavailable prerequisite as unavailable rather
  than as "nothing needs protecting".
- **No cleartext traffic.** `android:usesCleartextTraffic="false"` is set on the application.
- **No backup exposure.** `android:allowBackup="false"`, with
  `res/xml/data_extraction_rules.xml` excluding every storage domain from cloud backup and
  device-to-device transfer.
- **Nothing sensitive in logs or UI.** Failures are reported internally and surfaced to the UI
  as generic messages, and no secret is ever committed to the repository.
- **Shrinking is on for release builds**, so release-only problems surface early rather than
  at the end of the project.

Any change that weakens one of these defaults must explain why in the same change.

## Cryptography

The cryptographic core is documented in [`docs/crypto/README.md`](docs/crypto/README.md); the
byte layout of encrypted data is specified in
[`docs/crypto/envelope-format.md`](docs/crypto/envelope-format.md).

Summary of what is in place:

| Concern | Implementation |
| --- | --- |
| Randomness | `SecureRandomGenerator` over the platform CSPRNG |
| Authenticated encryption | AES-256-GCM, fresh random 96-bit nonce per operation, 128-bit tag |
| Encrypted format | Versioned, self-describing envelope with purpose binding (v1) |
| Key protection | Android Keystore AES-256-GCM keys that cannot be exported |
| Key wrapping | Content keys wrapped by device keys (GCM) or in-process keys (HKDF-SHA-256 + HMAC-SHA-256) |
| Credential KDF | PBKDF2-HMAC-SHA-256, 600 000 iterations, 128-bit salt |
| Recovery | Independent 256-bit recovery key sealing the same content key |

Principles the layer is held to:

- Callers never construct ciphers, choose nonces or see key material; the APIs make the classic
  AEAD mistakes unavailable.
- Unknown versions, schemes, algorithms and purposes are hard failures. There is no fallback to a
  weaker algorithm.
- Nothing persists secret material: no credential, no raw recovery key, no unprotected key.
  Persistence decisions belong to the stages that own the data.
- Erasure is best effort. The code clears live buffers and keeps secret lifetimes short, and the
  documentation says plainly that a managed runtime cannot guarantee zeroisation.

## Credential protection

The primary credential — a PIN, a password or a pattern, exactly one of them — is enrolled,
verified and changed through the `CredentialManager` domain contract. The design, the byte layouts
and the threat model are documented in
[`docs/credential/README.md`](docs/credential/README.md).

| Concern | Implementation |
| --- | --- |
| Methods | PIN, password or pattern; exactly one active at a time |
| Derivation | The Stage 2 PBKDF2-HMAC-SHA-256 service; the derived key is never stored |
| Stored material | Credential type, KDF parameters, random salt and a one-way verifier — `HMAC-SHA-256(derivedKey, label ‖ type)` |
| Storage | Two small files in the private directory, written atomically; no credential, key or recovery material |
| Verification | Constant-time comparison; a wrong credential, a wrong type and a tampered record are indistinguishable |
| Change | Authenticates the current credential first; the previous credential stops working immediately |
| Attempts | Two free attempts, then a capped exponential delay; reset only on success; never a permanent lock |
| Reset | Deliberately absent — removal goes through recovery, which is a later stage with its own threat model |

## Biometric unlock

Biometric unlock is a **secondary** path through Android's own `BiometricPrompt`. The primary
credential stays authoritative: biometrics can only be turned on when a credential exists, they can
never replace one, and an invalidated key falls back to the credential rather than to a bypass. The
full design record — including the key parameters, the byte layout of what is stored and the
handling of an enrolment change — is in [`docs/biometric/README.md`](docs/biometric/README.md).

| Concern | Implementation |
| --- | --- |
| Prompt | AndroidX `BiometricPrompt` with a `CryptoObject`; never a Nivara-drawn prompt |
| Key | AES-256-GCM in the Android Keystore, strong biometric required for **every** use, invalidated by a biometric enrolment change, never exportable |
| Stored material | One 71-byte record holding an encrypted random token and its IV — no key material, no biometric data |
| Primary credential | Required before enabling, untouched by disabling, and the fallback in every failure case |
| Failure policy | Five free failures, then one attempt per 30 seconds; reset by success or by a successful primary-credential authentication |
| Android's lockout | Reported as the platform's, never bypassed, never shortened, never claimed as clearable |
| Failures vs credential | Separate counters, separate files, separate types — a biometric failure never counts as a credential failure |

## Sessions

Nivara keeps exactly one authentication session, in memory, behind a `SessionManager`. A primary
credential success and a biometric success both arrive at the same gate; nothing else opens it. The
session holds the factor that opened it and when it ends — no credential, no key, no token — and it
is never written anywhere, so a recreated process is unauthenticated. The rules, the boundary and
Quick Lock are documented in [`docs/session/README.md`](docs/session/README.md).

| Concern | Behaviour |
| --- | --- |
| Established by | A successful primary-credential or biometric authentication, and nothing else |
| Timeout | Five minutes, by default, as a configurable value; valid strictly before expiry |
| Extended by | Nothing — a new authentication starts a new session with a new deadline |
| Quick Lock | `sessionManager.lockNow()`: immediate, idempotent, and reusable by later features |
| Persistence | None. No preferences, files or database; ending the process ends the session |
| Failure counters | Never read, advanced or reset by the session; credential and biometric policies are untouched |

## Repository checks

`tools/verify_nivara.py` runs a set of fast static checks that need no JDK: resource and version
references, package/directory agreement, and cryptographic hygiene (no predictable randomness, no
non-GCM cipher modes, no credentials converted to `String`, no logging in security code, no inline
key material).

```bash
python3 tools/verify_nivara.py
```

`tools/crypto_reference.py` is an independent implementation of the envelope format and the
credential KDF. It generates the known-answer vectors that the unit tests assert against, so the
Kotlin code is checked against a second implementation rather than against itself.

## Continuous integration

The workflow in [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) builds
the debug and release variants, compiles the instrumented tests, runs the unit tests and runs
lint.

It is intentionally not wired to every push: Gradle builds are expensive and nothing is
learned by rebuilding the same code repeatedly. A run is triggered when a change to `main`, or to
an open pull request, touches build configuration — Gradle, the version catalog, the wrapper or
the module build files — the repository checks under `tools/`, or the workflow itself. It can also
be started manually from the Actions tab whenever a change needs verification.

## Contributing

- Keep the dependency list short; add a library only when it removes more code than it adds.
- Prefer platform and AndroidX APIs over new dependencies.
- Keep `domain` free of Android types and keep screen composables stateless.
- Add a test when you add logic.
- Open a pull request; do not commit directly to `main`.

## License

Nivara is released under the [MIT License](LICENSE).

Copyright (c) Ashish Kumar
