# Nivara

Nivara is a native Android application for privacy and security. It is built to keep a
person's private space — locked apps, hidden apps, and encrypted files — on their own device,
under their own control.

The project is developed in the open. Everything that ships is written for the platform: no
cross-platform wrappers, no unnecessary third-party UI frameworks, and no dependencies that are
not justified by code that exists.

## Capabilities

Nivara protects three things on the device: the applications a person uses, the applications a
person does not want seen, and the files a person keeps — locked apps, hidden apps, and an
encrypted vault — all of it offline, under one credential the person chose.

What the application does:


- the application shell — home and about screens, navigation, Material 3 light/dark theme;
- reporting the device's screen-lock status;
- enrolling, verifying and changing one primary credential — a PIN, a password or a pattern —
  with a documented storage format and attempt throttling;
- biometric unlock through Android's own `BiometricPrompt`, gated by a Keystore key that requires
  a strong biometric for every use, with its own failure policy and explicit handling of an
  invalidated key;
- one in-memory authentication session, established by a primary-credential success or a
  biometric success, expiring on a fixed timeout and ended immediately by Quick Lock;
- launcher-application discovery, the Usage Access and overlay capabilities, and the preparation
  screen that reports what App Lock needs and starts and stops protection;
- App Lock detection: which application is in the foreground, whether the protected set requires
  authentication for it, and a published requirement for the layer that presents it;
- the App Lock protection surface: one secure window above the protected application, its
  authentication routed through the existing credential and biometric layers into the existing
  session, with the requirement bound to the application it was raised for;
- the App Lock settings screen: the applications the device can launch with the protection the
  stored set gives each of them, search and two orderings, and protect/unprotect through the same
  repository detection reads — a change requiring a valid session, reading the list requiring
  none;
- hidden-application management: every application the device can launch, which of them Nivara is
  asked to keep out of sight, search and two orderings, and hide/unhide through the one repository
  that stores that decision — a separate record from App Lock, requiring a valid session to change
  and none to read, and honest that Android's own launcher is not affected by it;
- a launcher: Nivara can be selected as the device's Home application, and its app drawer draws
  the applications the device can launch minus the ones marked hidden, with search, two orderings,
  applications opened through the platform's own launcher facilities, and a normal route into
  Nivara's settings — the drawer failing closed, and drawing nothing at all, when the hidden set
  cannot be read;
- an application identity: Nivara's launcher entry can present a benign, ordinary name and icon —
  a notes application, a calculator, a weather application — instead of its own, so a glance at the
  home screen does not announce what the application is for. It is presentation and nothing more:
  the package, the data and the signature are unchanged, Android's settings and the package manager
  still list Nivara, and the screen that offers it says so. What protects Nivara is unchanged too:
  the credential, biometric unlock, App Lock and the session timeout, none of which camouflage
  bypasses and none of which it weakens;
- vault storage: the user chooses one folder through Android's own folder picker, and Nivara keeps a
  durable reference to it and nothing else. Inside that folder it creates exactly two areas — an
  authenticated, versioned metadata record and a place for encrypted content — and it never chooses a
  location by itself, never falls back to another one, and never replaces a vault it can open. The
  vault's key is generated on the device and stored only wrapped under a key that never leaves
  Android's Keystore. What is at the folder is reported as one of a fixed set of states, so a record that
  cannot be opened is never drawn as an empty vault: damaged metadata, a lost platform key, an
  unfinished setup, a newer format, unreachable storage and a revoked grant are each their own
  answer, and none of them deletes or repairs anything. One document at a time is then imported —
  chosen through Android's own picker, read once, encrypted as a bounded stream and recorded in an
  authenticated index — and a listed file can be opened again: pictures, video and audio through the
  platform's own decoders over the vault's decryption, text as a bounded preview, PDF as rendered
  pages served through a proxy file descriptor. Everything else is described with its facts rather
  than guessed at. Files can then be organised: they are grouped into **albums** — a title, a random
  identity and a list of references, kept in a small authenticated record of its own and never a copy
  of a file's facts — the list is **searched** by name, type and kind (and albums by title), and it is
  **ordered** by name, size, arrival time or type, either way. An album is a list and nothing else:
  deleting one, or taking a file out of one, cannot delete, move or re-encrypt a file, and the
  confirmation says so. Searching, sorting and opening albums never decrypt anything, and no album
  state is ever drawn as "no albums" when it cannot be read. A file can then be moved to **Trash**
  and restored: the move is a state and nothing more — the encrypted file stays where it is with its
  name, its albums and its identity, nothing is deleted, nothing expires, and a trash record that
  cannot be read is never drawn as an empty trash; sharing does not exist, so the screen says
  nothing it does not do. And a vault can be **recovered**: the vault's own key is sealed under a
  recovery code the user is shown once, and after a reinstall — when the app's own state is gone
  but the folder is not — the vault screen offers the way back in: choose the folder, confirm the
  vault by its fingerprint, enter the code, and the same vault reconnects with the same identity,
  files, albums and trash. Recovery never creates, replaces, re-encrypts or repairs anything, and
  a wrong folder, a wrong code or a damaged record is its own typed refusal;
- the cryptographic layer that later features are built on: AES-256-GCM authenticated encryption
  with a versioned envelope format, secure randomness, Android Keystore key management, key
  wrapping, PBKDF2 credential derivation and the recovery-key foundation.

Biometric unlock never replaces the primary credential and never unlocks anything by itself, and
the session is not one either: it is an authorization state held only while the process lives, and
it holds no credential, no key and nothing on disk. Detection decides and publishes; the surface
that presents a requirement is separate from it, holds no credential of its own and can do exactly
one thing with an authentication result — hand it to the session gate. The vault consumes the layers described in
[`docs/crypto/README.md`](docs/crypto/README.md),
[`docs/credential/README.md`](docs/credential/README.md),
[`docs/biometric/README.md`](docs/biometric/README.md),
[`docs/session/README.md`](docs/session/README.md),
[`docs/applock/README.md`](docs/applock/README.md),
[`docs/apphide/README.md`](docs/apphide/README.md),
[`docs/launcher/README.md`](docs/launcher/README.md),
[`docs/camouflage/README.md`](docs/camouflage/README.md),
[`docs/vault/README.md`](docs/vault/README.md) and
[`docs/ui/README.md`](docs/ui/README.md). Hiding an application means Nivara's own
drawer leaves it out; Android's launcher still shows every application, and both the screens and the
documentation say so. Camouflage means Nivara's own launcher entry shows a different name and icon;
Android still lists Nivara by its package, and the screen that offers it says that too. Vault storage
means the vault lives in a folder the user picked, protected by encryption rather than by its
location — the folder itself is not a secret, and the screen that configures it says so. Opening an
imported file means reading it back through the same decryption that wrote it, in bounded pieces, with
no plaintext copy anywhere: what Nivara can actually draw, play or read is listed below, and anything
else is described rather than guessed at.

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
│   ├── app/                    Installed-application model, package-name rule, ordering, search
│   ├── permissions/            Usage Access and overlay capabilities, setup aggregate
│   ├── applock/                Protected set, detection, decision rule, overlay contract
│   ├── apphide/                Hidden set, its repository contract and its failure cases
│   ├── launcher/               What a launcher may draw, and the fail-closed rule for it
│   ├── camouflage/             What an identity is, and which one the device is presenting
│   └── vault/                  What is at the vault root, what a stored file is, and how it is read
├── data/                       Platform-backed implementations of those contracts
│   ├── app/                    Launcher-entry discovery through the package manager
│   ├── applock/                Usage-event detector, protected set store, monitor, service
│   ├── apphide/                The hidden set's versioned file format and its file repository
│   ├── credential/             Credential record, counters and verifier
│   ├── biometric/              Android's prompt, the NVBT record and its store
│   ├── permissions/            Usage Access app-op check, overlay check, settings entry points
│   ├── session/                The in-memory session manager
│   ├── vault/                  The storage seam, the record and index codecs, the import pipeline
│   │   └── viewer/             The platform decoders: images, the media stack and documents
│   └── security/               JCA, Android Keystore and device state
└── ui/                         Compose UI
    ├── NivaraApp.kt            Root composable: app bar + navigation host
    ├── theme/                  Material 3 colour schemes, typography and shapes
    ├── navigation/             Destinations and the navigation graph
    ├── components/             Shared design tokens, motion, state views and section headers
    ├── home/                   Home screen, state and view model
    ├── about/                  About screen
    ├── credential/             Enrolment, verification and change screens
    ├── biometric/              Biometric settings, state and view model
    ├── applications/           Application icons and the two orderings, shared by both lists
    ├── applock/                App Lock preparation screen, state and view model
    │   ├── management/         Choosing which applications are protected
    │   └── overlay/            The protection window, its lifecycle and its content
    ├── apphide/                Choosing which applications Nivara keeps out of sight
    ├── launcher/               The Home activity, the home surface and the app drawer
    ├── camouflage/             Choosing the name and icon Nivara presents under
    ├── vault/                  Choosing the vault folder, importing a file, the list and the viewer
    │   ├── recovery/           Reconnecting a vault after a reinstall with its recovery code
    │   └── viewer/             The viewer's state, wording and controls
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

The App Lock protection surface is covered the same way: which capability answers mean the
requirement can be presented (and that a missing or unreadable grant is never "nothing to
protect"), one occasion producing one request, a repeated requirement producing no second window,
a window following a new request rather than being rebuilt, removal on every ending and cleanup
that is safe to run twice, the routing of a primary and a biometric outcome into the session gate
with nothing else touched, results for a superseded request opening nothing, and expiry and Quick
Lock raising a fresh requirement. The window's platform half — `WindowManager`, Compose inside an
overlay, the Back key and the home intent — is asserted by instrumented tests that are compiled but
only run when a device is attached, and is declared unverified until then.

The App Lock settings layer is covered the same way: the two orderings and their tie-breaker, the
search rule, what a row may claim for an application in every combination of stored set and missing
capability, and the screen's state machine — a discovery failure that never deletes protection, a
stored set that cannot be read and is therefore never reported as empty, a change that is written
through the repository and read back from it, a stale row that writes nothing, and the session
policy that lets the list be read by anyone and a change be made only while the gate is open.

The hidden-application layer is covered the same way: the package-name identity rule and its
refusals, the three outcomes of reading the stored set (and the rule that an unreadable one is never
an empty one), the file format's rejection of every damaged shape — unknown magic or version,
truncated, trailing bytes, a broken checksum, an implausible count, a name that is not a package, a
duplicate entry — against real files, idempotent hide and unhide, serialised concurrent changes, a
write that cannot be completed leaving the previous set authoritative, all four combinations of
protected and hidden side by side, and the screen's state machine: what a row may claim in every
combination of stored set and discovery, a search result that is never confused with an empty
device, a stale row that writes nothing, a change that is read back from the repository, and the
session policy that lets the list be read by anyone and a change be made only while the gate is
open.

The launcher is covered the same way: the rule that decides what may be drawn, in every
combination of hidden set and reveal, including the two fail-closed outcomes and the four empty
states; the same rule against the production hidden and protected stores on real files; and the
launcher's state machine — what is withheld, what a reveal does and does not change, expiry and Quick
Lock ending one, a screen that never opens or extends a session, launching by exact package name, and
a stale row that writes nothing. The instrumented suites for the Home contract and the launcher's
composition are compiled but not executed: no device or emulator is available, so nothing about Home
selection, launcher resolution, icon rendering or lifecycle is claimed as verified.

Instrumented tests cover what only a device can prove: Android Keystore key generation,
non-exportability, invalidation detection and use through a cipher, and that the biometric key
refuses to produce output until Android has authorised a single operation. They are never simulated
on the JVM, and the flows that need a person to present a biometric remain manual.

## Security defaults

Security behaviour is built into the project's defaults rather than added at the end:

- **Two permissions, each justified by a feature that exists.** `PACKAGE_USAGE_STATS` makes Nivara
  visible in Android's Usage Access list and lets App Lock recognise the application in the
  foreground; `SYSTEM_ALERT_WINDOW` lets the App Lock protection surface be drawn above the
  application being protected, which is the only unprivileged way Android offers to do that. Both
  are granted by the user in Android's own settings screens and neither is ever requested at
  runtime. Package visibility is extended with a single `<queries>` launcher-intent signature rather
  than `QUERY_ALL_PACKAGES`, and every permission has to be justified; the reasons are recorded in
  [`docs/applock/README.md`](docs/applock/README.md) and checked by the repository verifier, which
  refuses any permission added without them.
- **One owner for the protected set, and one way in.** Which applications are protected lives in
  exactly one place — the repository the detection service reads — and the settings screen reads and
  writes through it rather than keeping a list of its own: no second store, no cached tick box, and
  no separate App Lock password. Reading the list needs no session, because the device's launcher
  already shows those applications to anyone holding the phone; changing the set needs a valid
  session from the existing gate, opened through the existing credential or biometric flows, because
  unprotecting an application is the one action that can undo App Lock for it.
- **Hiding is recorded, never enforced.** Nivara keeps one set of package names — the applications
  the user asked to keep out of sight — in one file, owned by one repository, written atomically and
  never read as empty when it cannot be read: a damaged or unreachable file refuses changes and
  claims nothing about any application rather than quietly treating the set as empty. Nothing in
  that path disables an application, touches another application's components or its enabled state,
  asks for accessibility or device administration, or manipulates Android's launcher; the record is
  a Nivara preference, and the screen and the documentation both say so. An application that
  disappears from the device keeps its entry — a discovery gap must never delete the user's
  configuration — and an application that comes back is hidden again. Changing the set requires the
  existing session; reading it requires nothing, because it is the same information the device's
  launcher already shows.
- **Detection without privileges it does not need.** The App Lock service is a plain, unexported
  started service: no foreground service, no notification and no accessibility service, and no
  battery-exemption request. Detector code holds no overlay window of its own — the very same
  service also owns the single protection window, whose only capability is the justified overlay
  grant. Detection reads usage events and never aggregate usage history and keeps no record of what
  was used; the protection surface shows nothing sensitive, is `FLAG_SECURE`, never names the
  protected application and never logs or transmits anything. An unavailable prerequisite is
  reported as unavailable rather than as "nothing needs protecting".
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
  Persistence decisions belong to the layers that own the data.
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
| Derivation | PBKDF2-HMAC-SHA-256; the derived key is never stored |
| Stored material | Credential type, KDF parameters, random salt and a one-way verifier — `HMAC-SHA-256(derivedKey, label ‖ type)` |
| Storage | Two small files in the private directory, written atomically; no credential, key or recovery material |
| Verification | Constant-time comparison; a wrong credential, a wrong type and a tampered record are indistinguishable |
| Change | Authenticates the current credential first; the previous credential stops working immediately |
| Attempts | Two free attempts, then a capped exponential delay; reset only on success; never a permanent lock |
| Reset | Deliberately absent — vault recovery reconnects a vault after a reinstall, but the credential itself has no reset path; a forgotten credential means enrolling a new one over a fresh installation |

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

## Hidden applications

Nivara records which applications the user wants kept out of sight. That record is a set of package
names and nothing else — no labels, no icons, no timestamps, no UI state — kept by one repository
that the management screen reads and writes through, and that Nivara's own launcher reads. The format, the failure cases and the boundary are documented in
[`docs/apphide/README.md`](docs/apphide/README.md).

| Concern | Behaviour |
| --- | --- |
| What is stored | Exact package names, in one versioned, checksummed file written atomically |
| What is not | Nothing on the device is changed: no component disabled, no package manager call, no accessibility, no device admin |
| Reading the set | Three outcomes — read (possibly empty), unreadable, unavailable — and never the last two as the first |
| Damage | Reported as damage: changes are refused, the file is left as it was found, and no application is claimed to be visible or hidden |
| Changing the set | The exact package name, through the repository, followed by a fresh read; idempotent, and never optimistic |
| Who may change it | Whoever holds a valid session from the existing gate; reading needs none |
| Relationship to App Lock | Independent: an application can be protected, hidden, both or neither, and neither screen touches the other's record |
| An application that is gone | Keeps its entry and is hidden again if it returns; the screen counts it rather than deleting it |
| Honesty | Android's launcher still shows these applications. This governs Nivara's own launcher, and the screen states that in as many words |

## The launcher

Nivara can be the device's Home application. The drawer is built from the same launcher-application
catalogue App Lock uses, with the applications marked hidden left out, and an explicit control that
shows them again for as long as Nivara is unlocked. Selecting Nivara as Home is a decision the user
makes in Android's own settings; nothing in Nivara adds itself as a preferred activity or changes any
system setting. The full contract is in [`docs/launcher/README.md`](docs/launcher/README.md).

| Concern | Behaviour |
| --- | --- |
| Home contract | `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT`, exported, and no other action or entry point |
| Discovery | The existing `ApplicationRepository`; no second scanner and no new package visibility |
| Filtering | The domain's launcher rule: the discovered catalogue minus the hidden set, by exact package name |
| When the hidden set cannot be read | Nothing is drawn at all — never the full catalogue |
| Showing hidden applications | An ordinary labelled control; needs a valid session from the existing gate |
| The reveal itself | In memory, session-bound, cleared by expiry and by Quick Lock, and gone after a process recreation |
| Stored state | Never written by the launcher; hiding and revealing change the device in no way |
| App Lock | Untouched and independent: a hidden application that is protected is still protected when opened |
| Android's own launcher | Unchanged. Hiding means Nivara's drawer leaves an application out, nothing more |

## Application identity

Nivara's launcher entry can present a benign, ordinary identity — a notes application, a calculator,
a weather application — instead of its own name and icon. Choosing one is an ordinary settings screen,
reached from the home screen and protected by the same session every other configuration change uses.

| Concern | Behaviour |
| --- | --- |
| What changes | The name and icon of the launcher entry that starts Nivara, and nothing else |
| What does not change | The package, the signature, the data, the credential, App Lock, hidden applications, the session timeout, the Home contract |
| Where the selection lives | In the platform's component state; Nivara keeps no file, no preference and no copy of it |
| Identities | Nivara's own, plus Notes, Calculator and Weather — a fixed set declared in the manifest |
| The default | Nivara's own identity, on a fresh install and after any state Nivara cannot vouch for |
| A device state that names no identity | Repaired to Nivara's own identity on the next read, never guessed at |
| Getting back | Always possible: from the launcher entry (one is always enabled), or from Android's application list, where Nivara is still Nivara |
| What it is not | A security boundary, a way to hide the application, or a way to hide anything from Android |

The full contract is in [`docs/camouflage/README.md`](docs/camouflage/README.md).

## Vault storage

The vault lives in **one folder the user chooses** through Android's own folder picker, and Nivara
keeps a durable reference to that folder and nothing else. No storage permission is involved, and the
folder can be on a memory card or in a synced location — which is also how the vault moves between
devices.

| Concern | Behaviour |
| --- | --- |
| Where the vault lives | The folder the user selected; Nivara never picks one, never falls back to another, and never moves a vault |
| What is created | Exactly two areas inside that folder: an authenticated metadata record and a place for encrypted content |
| The record | Versioned and authenticated; a vault's identity and wrapped key, sealed by the existing cryptographic layer, with nothing secret in the clear |
| The key | Generated on the device, stored only wrapped under a key that never leaves Android's Keystore |
| Creating a vault | Explicit, session-gated, and reported as done only after the record has been read back and opened |
| Opening a vault | A read; nothing is created, repaired, deleted or replaced while looking |
| What is at the folder | States from "no folder chosen" to "a newer Nivara wrote this" — damage, a lost key and unreachable storage are never drawn as an empty vault |
| Importing a file | One document at a time, chosen through Android's own picker; read once, never moved or modified, and encrypted into the vault in bounded pieces |
| What a stored file is | One encrypted object named after a random per-file identifier, listed by an authenticated index that never holds a key or a source reference |
| When a file counts as imported | Only after its object is complete and read back, and the index that names it has itself been read back and opened |
| The list on the screen | Name, type, size and arrival time, plus honest states for an index that cannot be read, a newer format, unfinished imports and encrypted files that are not listed; a tap opens the file |
| Organising it | **Albums** of files under a title of the user's own, a **search** box over names, types and kinds (and over album titles on the albums surface), and an **order** by name, size, arrival time or type, ascending or descending |
| What an album is | A title, a random identity and a list of file references, kept in its own versioned, authenticated record beside the index — never a folder, never a copy of a file's facts, and never a second key |
| What an album can do | Create, rename, delete, add a file to one, take a file out of one; a file may be in none, one or several, and an album that was made on purpose is not tidied away when it empties |
| What deleting an album does | Deletes that list. The encrypted objects, the index and every other album's membership are untouched, and the confirmation says so before it happens |
| A file the vault no longer lists | Shown where an album holds it, as no longer in the vault, and kept until somebody takes it out of that album on purpose — nothing repairs it silently |
| Moving a file to Trash | A state, not a deletion: the file leaves the list, the search and the albums' active contents, and its encrypted object, its name, its identity and its album memberships stay exactly as they were |
| What the trash is | One small authenticated record of its own beside the index — identifiers and the moment they left the active collection, sealed under its own purpose with the vault's one key, never a copy of a file's facts |
| Restoring a file | The reference leaves the record and the file is active again, under the same identity, in its albums as before; a record that cannot be read is never drawn as an empty trash, and nothing in the vault permanently deletes, empties or expires a file |
| Searching and sorting | In memory, over the metadata that was read: no decryption, no second read of the vault, no fuzzy matching, no ranking and no record of what was searched or opened; the trash has its own search and its own order, both over the trash alone |
| The order | Total and reproducible: every comparison ends in the file's own identifier, so two files that look identical still come out in the same sequence every time |
| Opening a file | Read back through the same streaming decryption that wrote it, in bounded pieces, with the session asked before each one and no plaintext copy on disk |
| Setting up recovery | Optional, session-gated, from the vault screen: the vault's own key is sealed under 256 random bits and the code is shown exactly once — Nivara keeps no copy, and the vault, its key and its content are unchanged |
| Coming back after a reinstall | Choose the vault's folder, confirm the vault by its fingerprint, enter the code: the vault reconnects with the same identity, files, albums and trash, and opens through the usual unlock — the code is not a session and does not replace the credential |
| What recovery never does | Create a vault over one that exists, regenerate a key or an identity, rewrite content, rebuild a record, or accept "decryption produced bytes" as proof: a wrong folder, a wrong code and a damaged record are each their own refusal |
| Losing the recovery code | The vault cannot be opened again once the installation's state is gone — there is no copy, no account and no bypass; the vault screen keeps offering setup while a vault has no recovery record |
| What can be shown | JPEG, PNG, WebP, GIF, BMP, HEIC and HEIF pictures (bounded decode); MP4, WebM, 3GPP, MPEG and Matroska video; MP3, AAC/M4A, WAV, OGG, FLAC and Opus audio through the platform's player; plain text, CSV, Markdown, XML and JSON as a bounded preview; PDF as rendered pages |
| What is described instead | Office documents, archives, and any file whose declared type is missing, malformed or unfamiliar: the facts are shown and the screen says plainly that this build has no viewer |
| When the session ends | Playback stops, every decoder and the content are released, and the viewer says the vault is locked; Quick Lock does the same instantly, and reopening goes through the credential screen that already exists |
| What it never does | No key, cipher or storage handle reaches a screen; no plaintext temporary file, cache or thumbnail is ever written; and no sharing, exporting or "open with" exists anywhere in the application |
| What it is not | A gallery, a second password, or a hidden route: the vault is reached from the home screen like any other settings screen, and its trash is a state a file is in — never a deletion, never an expiry |

The full contract — the structure, the metadata format, the content format, the index format, the
album record's own format, the import pipeline, the classification rules, what each viewer does, the
organisation rules, the failure states and the verification status — is in
[`docs/vault/README.md`](docs/vault/README.md).

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
