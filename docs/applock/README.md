# App Lock: discovery, preparation and detection

This document records the platform decisions behind App Lock: what Nivara asks the system, what it
declares in the manifest, what it decides, and what it deliberately does not do yet. It is the
durable reference for the stages that follow, not a description of the UI.

It covers two stages, in order:

* **[Stage 6: discovery, permission setup and preparation](#stage-6-discovery-permission-setup-and-preparation)** —
  which applications exist, whether Usage Access is granted, and where the user changes that.
* **[Stage 7: detection and protection decisions](#stage-7-detection-and-protection-decisions)** —
  which application is in front, whether it needs authentication, and how that is published.

Neither stage draws anything: the authentication prompt and the overlay belong to the stage that
presents them.

# Stage 6: discovery, permission setup and preparation

## Scope of this stage

The preparation screen answers three questions and nothing more:

1. Which applications can the user open on this device?
2. Has the user granted Nivara Usage Access?
3. What is missing, and where does the user change it?

Choosing applications and locking them arrives later. Nothing in this stage blocks, hides, monitors
or records an application, and nothing in it reads usage history.

## Application discovery

* **Launcher entries only.** The query is Android's launcher intent (`ACTION_MAIN` with
  `CATEGORY_LAUNCHER`), resolved through `PackageManager.queryIntentActivities`. That is the set a
  person can actually open. Packages without a launcher entry point are never enumerated, so a
  system service or a library is not presented as something to lock.
* **One entry per package.** An application may publish several launcher activities; they are one
  application and appear once.
* **Nivara excludes itself.** Nivara has a launcher entry point, so it would otherwise appear in
  its own list. A lock that locked its own launcher would be a support call rather than a feature,
  and Stage 7 needs Nivara to stay reachable while another application is locked. If a later stage
  chooses to show Nivara in the list, the exclusion is one comparison in
  `AndroidApplicationRepository` and this paragraph must change with it.
* **Identity is the package name.** The label is only what is drawn. Two applications can share a
  label, a label can be changed by the user or by the application itself, and a label is not
  unique; a package name is. `InstalledApplication` therefore compares and hashes by package name.
* **Labels degrade honestly.** The launcher entry's label is used first, then the application's
  own label, then the package name. Nothing is invented, and a blank label is never shown.
* **Ordering.** Label, case-insensitively, then package name as the tie-breaker — a total order, so
  an unchanged device produces the same list in the same sequence. The App Lock screen's sorting
  options are that stage's work and build on the same identity rule.
* **Search-ready, not search.** The package name and label are both searchable through
  `ApplicationSearch`, an Android-free helper with plain substring matching: no ranking, no fuzzy
  matching, no index. There is no search field yet.
* **Tolerating change.** An application that is uninstalled or updated while the list is being
  built can fail to resolve; it is skipped and the rest of the list still returns. Only a failure
  of the query itself is reported as a failure, and a failure is never drawn as an empty device.
* **Refresh, not a service.** The list is rebuilt on demand: when the screen loads and whenever the
  screen is resumed. There is no background package-change receiver and no watch on the package
  manager.
* **In memory only.** The list is returned to the caller and dropped. It is not cached, written to
  a file, put in a database, backed up or sent anywhere.

## Package visibility

From API 30 Android hides installed applications from a query by default. Launcher applications
are not automatically visible, so without a declaration the discovery query would return nothing
on a modern device.

The manifest therefore declares exactly one intent signature:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

This is the narrowest mechanism that answers the question. `QUERY_ALL_PACKAGES` is **not** used:
it would expose every package on the device — including non-launchable system internals — to answer
a question about the applications a person can open, and Google Play treats the installed
application list as personal and sensitive user data.

Two deliberate consequences:

* The settings entry point does not need visibility. Nivara starts Android's Usage Access screen as
  an implicit intent and handles the case where nothing can handle it (see below); it never calls
  `resolveActivity` for it, which would require adding the settings package to `<queries>`.
* If a future stage genuinely needs visibility of a specific package (for example a system
  component it must bind to), the narrow additions are a `<queries>` entry for that package name or
  another intent signature — never `QUERY_ALL_PACKAGES`.

## Permissions

Nivara declares one permission: `android.permission.PACKAGE_USAGE_STATS`.

| Question | Answer |
| --- | --- |
| Why does it exist? | It makes Nivara visible in Android's Usage Access list and allows a later stage to read usage statistics. The preparation screen only reads whether the grant exists. |
| Which feature requires it? | App Lock. The detection service that recognises the foreground application (next stage) is what actually consumes usage data. |
| How is it granted? | It is **not** a runtime permission. The user grants it in Android's Usage Access settings; it is checked as an application operation. |
| Is it requested at runtime? | No. `requestPermissions` is never called for it, and no code path in Nivara can grant it. |
| Does it need to be declared? | Yes — without the declaration the app-op can never be granted and Nivara does not appear in the Usage Access list. |
| Is it needed on Android 9+? | Yes, on every supported version. The `PACKAGE_USAGE_STATS` app-op exists from API 21 and the modern check (`checkOpNoThrow`) is available from API 19. |
| Does it expose user data by itself? | No. The grant is a capability. Nothing in this stage reads statistics, and there is no usage-history feature. |

The declaration carries `tools:ignore="ProtectedPermissions"`: the permission is protected on
purpose, and the user — not the application — is the one who grants it.

The grant state is read with:

```kotlin
appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
```

`unsafeCheckOpNoThrow` and its variants are deprecated in API 36; the non-throwing
`checkOpNoThrow` overload is the current API and reports the same mode without requiring the grant
first. Only `MODE_ALLOWED` becomes `Granted`. `MODE_ERRORED` becomes `Unavailable`, because in that
state the capability can neither be used nor changed, and reporting it as "not granted" would hide
a broken check behind a normal answer. Any other mode — ignored, default, or one this build does
not know — is `NotGranted`.

`UsageAccessStatus.Unavailable` is never treated as a grant, and the screen never claims to know
more than the platform told it.

## Entry point into Android's settings

The user changes the grant in Android's own screen, opened with
`Settings.ACTION_USAGE_ACCESS_SETTINGS`:

* The intent is started as-is. No package URI is attached, because the deep link is not supported
  on every device and a failure to resolve it would look like a broken feature.
* A `ActivityNotFoundException` (or any other failure to start) becomes a generic message. The raw
  platform exception is never shown to the user.
* A success means the screen was opened — **never** that the grant changed.
* The state is re-read when the screen is resumed, which is how a return from Android's settings is
  noticed. There is no polling and no automation of the settings screen.

## Overlay permission: deferred

`SYSTEM_ALERT_WINDOW` is **not** declared in this stage, and there is no overlay capability check.

The reason is that nothing in Stage 6 draws above another application, and the permission is only
meaningful when something does. The stage that draws the authentication prompt over a locked
application (Stage 8) is the first that can honestly justify it, and it must add, in the same
change: the capability check, the settings entry point
(`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`), a clear explanation of what the user is granting,
and the fact that the overlay permission itself locks nothing — it only allows a window to be
drawn.

Keeping the two apart matters. Usage Access *observes* which application is in the foreground;
overlay permission *draws* above another application. They are different capabilities, granted in
different screens, and neither is a substitute for the other.

## Battery optimisation: nothing added

No battery-related permission, exemption request or manufacturer-specific auto-start intent is
declared, and none is needed by the current architecture:

* Detection is driven by usage events while the user is using the device; the foreground
  application changes as a direct consequence of something the user just did, and the platform
  delivers that to a running process.
* `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a Play-policy-sensitive request and would be
  unjustified here; manufacturer "auto-start" screens are undocumented intents and would be
  guesswork.
* Nivara does not promise reliable background execution on every device, because no application can
  make that promise across every vendor's power management.

If a later stage demonstrates on real devices that locking fails while Nivara is stopped, that
finding — with the device and the reproduction — is what should justify a narrow capability check.

## Session and screenshots

* **No authenticated session is required on the preparation screen.** It exposes no secret: the
  capability state and the labels of applications that the device's launcher already displays to
  anyone holding the phone. Requiring a credential here would also put the prerequisite screen
  behind the very credential a first-time user may not have set up yet. App Lock itself — the
  prompt that opens another application — is the surface that must consult the existing
  `SessionManager` gate and `lockNow()`, and that is the next stages' requirement.
* **The screen uses the existing screenshot protection.** `SecureScreenEffect` (the single
  `FLAG_SECURE` implementation) is applied where the screen lists applications, because Android
  treats the installed-application list as personal data. No second implementation exists and none
  is introduced.


## What is not verified here (Stage 6)

Discovery, the Usage Access app-op check and the settings intent have not been executed on a
physical device or emulator. Compilation in CI is not device verification. The JVM
tests cover the Android-free logic — the model and its identity rule, ordering, search matching,
the permission-state aggregation and the mode-to-status mapping — and the instrumented tests that
exercise the platform are compiled, not run, unless a device is attached. In particular, whether a
particular device's Usage Access screen is reachable and whether its app-op reports `MODE_ALLOWED`
after a grant remain unverified until they are observed on hardware.

# Stage 7: detection and protection decisions

Stage 6 established what App Lock needs; this stage establishes what it concludes. It watches which
application is in the foreground, decides whether that application needs authentication, and
publishes both — and it stops there. No overlay, no prompt, no blocking: the presentation layer is
Stage 8's work.

## Protected applications and their identity

* **The package name is the identity**, exactly as in discovery. A label, a launcher activity, an
  icon, a list position and a process id are all things that change or repeat; the package name is
  what the platform reports for the application in the foreground, so it is the only thing that can
  be matched against and the only thing stored.
* **The stored set holds package names and nothing else** — no labels, no icons, no timestamps, no
  per-application state, no authentication material. A protected application that is later
  uninstalled or disabled simply never becomes the foreground application again; its entry stays
  until a settings screen removes it, because silently deleting the user's configuration is worse
  than keeping a name that no longer resolves.
* **Nivara excludes itself from protection** (see below), and system packages are never protected
  automatically: protection is opt-in per package name, and the launcher-application list the user
  chooses from does not contain the shell, the system UI or permission screens.

## Persistence decision

The set is persisted, and the decision is deliberate:

* it has to survive a restart, because a protection decision made after the process was recreated
  must use the list the user configured rather than an invented one;
* it is the smallest thing that could be written: package names, in the application's private
  directory, excluded from backup and device transfer with the rest of the application's data;
* it is written **atomically** — a temporary file, flushed and synced, then renamed into place —
  through the same helper the credential layer uses, so a reader sees the previous set or the new
  one and never a half-written list;
* changes are serialised, so two edits cannot interleave into a set that contains neither view.

Format (`applock/protected-applications.nvl`, versioned, checksummed, deterministic):

```
  offset  size  field
  ------  ----  ---------------------------------------------------------------
       0     4  magic "NVPL"
       4     1  format version (1)
       5     4  protected application count, big-endian
       9     n  each application: 1-byte name length + UTF-8 package name
   9+n      4  CRC-32 of everything before it, big-endian
```

**A damaged file fails closed.** A missing file is an empty set — a device where nothing has been
protected yet. A file that exists but cannot be read exactly is a *failure*, and the failure has two
consequences that matter:

* the monitor publishes `Unavailable`, so Nivara stops claiming to know what needs protecting,
  rather than reading the damage as "nothing is protected";
* a change is refused rather than overwriting configuration that cannot be read, so a corrupt file
  cannot be silently replaced by a shorter one the user did not ask for.

Repair belongs to the settings screen that will present this set. What is explicitly rejected is the
tempting alternative — treating an unreadable file as an empty set — because that turns a storage
fault into a silent loss of every protection the user configured.

The session is **not** stored anywhere, here or elsewhere: whether Nivara is unlocked is the session
manager's answer, it lives in memory, and it is never written next to the protected set.

## Foreground detection

Android has no unprivileged callback for "an application came to the foreground", so detection asks:

| Concern | Decision |
| --- | --- |
| API | `UsageStatsManager.queryEvents` — individual transitions, never aggregate statistics |
| Events used | the two foreground transitions only; everything else is ignored |
| Method | poll every 1 s (`AppLockDetectionPolicy.DEFAULT_POLL_INTERVAL_MILLIS`) |
| Minimum interval | 250 ms, enforced by the policy's own validation: faster is a busy loop, not detection |
| Cold start | read the last 30 s to find the application already in front |
| Window bound | one query never covers more than that lookback, so a suspended process re-reads a bounded slice instead of walking minutes of events |
| Kept state | the application currently in front, and the end of the last query — nothing else |
| Ordering | transitions are applied oldest first, so the newest one decides; an event with no usable package name is ignored |

The two foreground transitions are `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED` (API 29 names for
`MOVE_TO_FOREGROUND` and `MOVE_TO_BACKGROUND`, same numbers, present on every supported version).
They are mirrored as plain integers so the mapping is unit-testable, and an instrumented test
asserts the numbers against `UsageEvents.Event` on the device — the mirror is not taken on trust.

**Misuse this rules out.** `queryUsageStats` (aggregate usage history) is not called anywhere: App
Lock needs the current application, not the device's record of what was used. The restricted
activity-manager calls (`getRunningTasks`, `getRunningAppProcesses`) are not used either, and the
verifier fails the build if any of these appear.

## Usage Access is a prerequisite, never an empty result

```
  UsageAccessStatus.NotGranted ─┐
  UsageAccessStatus.Unavailable ├─→ AppLockState.Unavailable(reason) ─→ no decision is made
  protected set unreadable ─────┤
  platform will not answer ─────┘
```

An unavailable prerequisite is never reported as "nothing is in the foreground" and never as "no
application is protected". The two are different facts, and conflating them would tell the user
their applications are protected while Nivara is blind. The monitor keeps checking on the same
interval while unavailable, because the grant is given in Android's own settings screen and the user
can return at any moment — recovery must not need a restart.

## Protection decision

One pure rule, four inputs, one memory:

```
  no foreground application                     → NoProtectionRequired
  Nivara itself in the foreground               → NoProtectionRequired
  foreground application is not protected       → NoProtectionRequired
  protected + a valid session                   → NoProtectionRequired
  protected + no valid session                  → AuthenticationRequired(application)
```

**Nivara's own package is excluded.** Nivara is in the foreground while the user browses it, and a
future App Lock prompt will be one of its screens; without the rule, Nivara would ask for
authentication to show its own question. The package name is injected from the platform in one place
(the composition root) rather than written down anywhere in the logic, so the exclusion is one
comparison and the tests can state it with a name of their own.

**System and transient packages are ordinary inputs.** The launcher, the system UI, a permission
screen or a package that cannot be resolved are all just package names that are not in the protected
set, so they need no protection and cause no loop. There is no special-casing by package type, and
nothing is protected automatically.

**The decision never authenticates anything.** It carries no session, no credential, no timer and no
UI: it cannot launch an activity, and it cannot show a prompt. It is a value for the layer above.

## Requirements and debouncing

The monitor publishes `AppLockState` and raises `ProtectionEvent.AuthenticationRequired` when a
protected application comes forward without a session covering it.

**A requirement is raised once per entry, not once per observation.** The rule keeps only the last
decision, so repeating the same situation produces no event: the presentation layer can never be
told to prompt a hundred times a second by a monitor that keeps looking. The memory clears as soon
as the situation is no longer the same — the user leaves the application, the session opens, the
application stops being protected, or Nivara itself comes to the front (its own prompt, for
instance). Coming back to a protected application afterwards is a new situation and raises the
requirement again.

Sequence, stated exactly as it behaves:

```
  protected app A, no session            → AuthenticationRequired(A)
  still in A, still no session           → no event (same requirement)
  session opens (authentication)         → NoProtectionRequired
  session expires or Quick Lock          → AuthenticationRequired(A), once
  leave A, open unprotected B            → NoProtectionRequired
  return to A while the session is valid → NoProtectionRequired (no second authentication)
```

There is no per-application unlock, no "last unlocked" timestamp and no unlock timer: whether a
protected application may be used is *only* the session's answer, read again on every observation.

## Session integration

* **One authority.** The monitor calls `SessionManager.currentState()` on every observation and
  holds nothing itself. The gate applies the timeout rule on read, so a session that ran out while
  the process was frozen is already closed when it is consulted.
* **No second state.** No unlocked flag, no per-application session, no authentication cache, no
  `appUnlocked` field. The verifier fails the build if App Lock declares a type whose name suggests
  otherwise (`*Session*`, `*Unlock*`, `*Authenticated*`).
* **Quick Lock** remains `SessionManager.lockNow()`. Detection observes the resulting
  `Unauthenticated` on its next look and re-raises the requirement; it never locks anything itself,
  never touches the credential, the biometric key or either failure counter, and a cancelled or
  failed authentication is not a case it invents policy for — Stage 3/4 outcomes stay where they
  are, and the next decision simply still requires authentication.

## Service architecture and lifecycle

`AppLockDetectionService` is a **plain started service**, not a foreground service. It owns no logic:
it starts and stops the application-scoped monitor, which is the single monitoring loop in the
process. Starting it twice leaves one loop; destroying it stops the loop and returns the state to
`Stopped`; a run that is stopped mid-observation cannot publish, because each run carries a
generation that only the current one may write under. The service is not exported and declares no
permissions or service type.

**Why not a foreground service yet.** It is the mechanism a later stage will need once something is
actually presented on screen, and it is not added now because:

* nothing consumes a protection decision yet, so a permanent notification would be a cost with no
  visible benefit;
* it needs permissions and a service type (`FOREGROUND_SERVICE` plus a declared type, and a
  notification the user must be able to see) that must be justified by the feature they serve, not
  by the fact that background work exists;
* a plain started service already covers what this stage can honestly claim.

**What is honestly claimed.** Android stops a background service some minutes after the application
leaves the foreground, and a process that is killed takes detection with it: `START_NOT_STICKY` means
Android will not recreate it on its own. Detection therefore runs while Nivara is in front and for
as long as the platform keeps the service alive afterwards — and *reliable* protection of
applications opened much later is not claimed, not verified, and not promised on any device or OEM.
The verifier refuses `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`
and `BIND_ACCESSIBILITY_SERVICE` until a stage documents why it needs them.

## Overlay decision

Unchanged: `SYSTEM_ALERT_WINDOW` is **not** declared, and no overlay capability is checked.
Detection produces an internal requirement and nothing draws above another application, so the
permission would entitle Nivara to something it does not do. Stage 8 owns the capability check, the
settings entry point, the explanation and the presentation — and must add all of them together,
with the documentation and the allow-list entry to match. The distinction stays explicit: Usage
Access *observes* which application is in front; overlay permission *draws* above another
application. Neither substitutes for the other, and a granted overlay permission locks nothing by
itself.

## Battery and OEM behaviour

Nothing added, and no reliability promised. Detection is driven by reading usage events while the
process is alive; no exemption is requested, no manufacturer auto-start screen is opened, no
undocumented intent is used. If a later stage demonstrates on real devices that protection fails
because Nivara is stopped in the background — with the device and the reproduction — that finding is
what should justify a narrowly scoped capability check. Until then, the honest statement is:
detection uses the supported Android mechanisms, and OEM-specific reliability has neither been
guaranteed nor verified.

## Resource discipline

One coroutine, one usage query per interval, one state flow. No wake locks, no tight loops (the
interval has a floor and a documented default), no unbounded coroutine creation, no package-manager
queries at all in the detection path, no usage-event history, and no Activity or Context held beyond
the application context the detector was built with. The monitor re-reads the protected set each
turn rather than caching it, which costs one small file read per second and removes an entire class
of staleness; the file is a few hundred bytes.

## What is not verified here

Stage 7 adds its own gaps to Stage 6's:

* **No foreground observation has been verified on a device.** The rule that says
  `com.example.camera` needs authentication is exercised on the JVM with values a test supplies.
  That Android would report that application, at that moment, is a claim no test in this repository
  makes — it needs a device and a person.
* **The usage-event numbers are asserted against the platform, but not their delivery.** The
  instrumented test proves `ACTIVITY_RESUMED` and `ACTIVITY_PAUSED` are the numbers the mapping
  uses; that a real device delivers them for the applications a user opens is a manual check.
* **Background reliability is not verified and not claimed.** Whether detection survives a
  particular OEM's power management has not been tested on any device, and the service is stop-and-
  forget by design (`START_NOT_STICKY`).
* **A cold start inside an already-open protected application** reports no foreground application
  until the next transition, and a window skipped while the process was suspended can only be
  recovered from the lookback slice. Both are accepted limits of a polling detector with no
  privileged callback; they matter to the stage that presents a prompt and are recorded here so that
  stage can decide whether a wider window is worth its cost.
* **The storage format is verified on the JVM**, with real files and real atomic writes, but not on
  a device's storage stack.
