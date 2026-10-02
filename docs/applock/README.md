# App Lock: discovery, preparation, detection and management

This document records the platform decisions behind App Lock: what Nivara asks the system, what it
declares in the manifest, what it decides, what the user can configure, and what it deliberately
does not do yet. It is the durable reference for what follows, not a walkthrough of the
screens.

It covers, in order:

* **[Discovery, permission setup and preparation](#discovery-permission-setup-and-preparation)** —
  which applications exist, whether Usage Access is granted, and where the user changes that.
* **[Detection and protection decisions](#detection-and-protection-decisions)** —
  which application is in front, whether it needs authentication, and how that is published.
* **[The protection surface and authentication flow](#the-protection-surface-and-authentication-flow)** —
  how a requirement is presented above another application, why that needs a declared permission,
  and how the authentication it asks for reaches the existing session.
* **[Choosing which applications are protected](#choosing-which-applications-are-protected)** —
  the settings screen: the applications the device has, what the stored set says about each of
  them, search, ordering, and the rules under which the set may be changed.

Discovery and detection draw nothing. The surface that presents a requirement is neither the first nor the
last chapter to touch the screen, and it remains the only place Nivara asks Android for a way to
appear above another application.

The document ends with the boundary between App Lock and hidden applications: the two
features share the discovery catalogue, the session gate and the screen conventions, and share no
stored state at all.

# Discovery, permission setup and preparation

## Scope

The preparation screen answers three questions and nothing more:

1. Which applications can the user open on this device?
2. Has the user granted Nivara Usage Access?
3. What is missing, and where does the user change it?

Choosing applications and locking them arrives later. Nothing in this work blocks, hides, monitors
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
  and detection needs Nivara to stay reachable while another application is locked. If later work
  chooses to show Nivara in the list, the exclusion is one comparison in
  `AndroidApplicationRepository` and this paragraph must change with it.
* **Identity is the package name.** The label is only what is drawn. Two applications can share a
  label, a label can be changed by the user or by the application itself, and a label is not
  unique; a package name is. `InstalledApplication` therefore compares and hashes by package name.
* **Labels degrade honestly.** The launcher entry's label is used first, then the application's
  own label, then the package name. Nothing is invented, and a blank label is never shown.
* **Ordering.** Label, case-insensitively, then package name as the tie-breaker — a total order, so
  an unchanged device produces the same list in the same sequence. The App Lock screen's sorting
  options are that work's and build on the same identity rule.
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
* If future work genuinely needs visibility of a specific package (for example a system
  component it must bind to), the narrow additions are a `<queries>` entry for that package name or
  another intent signature — never `QUERY_ALL_PACKAGES`.

## Permissions

Nivara declares one permission: `android.permission.PACKAGE_USAGE_STATS`.

| Question | Answer |
| --- | --- |
| Why does it exist? | It makes Nivara visible in Android's Usage Access list and allows later work to read usage statistics. The preparation screen only reads whether the grant exists. |
| Which feature requires it? | App Lock. The detection service that recognises the foreground application (detection) is what actually consumes usage data. |
| How is it granted? | It is **not** a runtime permission. The user grants it in Android's Usage Access settings; it is checked as an application operation. |
| Is it requested at runtime? | No. `requestPermissions` is never called for it, and no code path in Nivara can grant it. |
| Does it need to be declared? | Yes — without the declaration the app-op can never be granted and Nivara does not appear in the Usage Access list. |
| Is it needed on Android 9+? | Yes, on every supported version. The `PACKAGE_USAGE_STATS` app-op exists from API 21 and the modern check (`checkOpNoThrow`) is available from API 19. |
| Does it expose user data by itself? | No. The grant is a capability. Nothing in this work reads statistics, and there is no usage-history feature. |

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

## Overlay permission: deferred here, decided in this work

`SYSTEM_ALERT_WINDOW` was **not** declared in this work, and no overlay capability was checked.

The reason was that nothing in this work draws above another application, and the permission is only
meaningful when something does. The protection surface revisited the decision when it built the surface that
presents an authentication requirement over a protected application, and declared the permission
there, with the evidence and the reasoning recorded in the protection-surface section below.

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

If later work demonstrates on real devices that locking fails while Nivara is stopped, that
finding — with the device and the reproduction — is what should justify a narrow capability check.

## Session and screenshots

* **No authenticated session is required on the preparation screen.** It exposes no secret: the
  capability state and the labels of applications that the device's launcher already displays to
  anyone holding the phone. Requiring a credential here would also put the prerequisite screen
  behind the very credential a first-time user may not have set up yet. App Lock itself — the
  prompt that opens another application — is the surface that must consult the existing
  `SessionManager` gate and `lockNow()`, and that is the next step's requirement.
* **The screen uses the existing screenshot protection.** `SecureScreenEffect` (the single
  `FLAG_SECURE` implementation) is applied where the screen lists applications, because Android
  treats the installed-application list as personal data. No second implementation exists and none
  is introduced.


## What is not verified here

Discovery, the Usage Access app-op check and the settings intent have not been executed on a
physical device or emulator. Compilation in CI is not device verification. The JVM
tests cover the Android-free logic — the model and its identity rule, ordering, search matching,
the permission-state aggregation and the mode-to-status mapping — and the instrumented tests that
exercise the platform are compiled, not run, unless a device is attached. In particular, whether a
particular device's Usage Access screen is reachable and whether its app-op reports `MODE_ALLOWED`
after a grant remain unverified until they are observed on hardware.

# Detection and protection decisions

The preparation work established what App Lock needs; detection establishes what it concludes. It watches which
application is in the foreground, decides whether that application needs authentication, and
publishes both — and it stops there. No overlay, no prompt, no blocking: the presentation layer is
the work.

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
  failed authentication is not a case it invents policy for — credential and biometric outcomes stay where they
  are, and the next decision simply still requires authentication.

## Service architecture and lifecycle

`AppLockDetectionService` is a **plain started service**, not a foreground service. It owns no logic:
it starts and stops the application-scoped monitor, which is the single monitoring loop in the
process. Starting it twice leaves one loop; destroying it stops the loop and returns the state to
`Stopped`; a run that is stopped mid-observation cannot publish, because each run carries a
generation that only the current one may write under. The service is not exported and declares no
permissions or service type.

**Why not a foreground service yet.** It is the mechanism later work will need once something is
actually presented on screen, and it is not added now because:

* nothing consumes a protection decision yet, so a permanent notification would be a cost with no
  visible benefit;
* it needs permissions and a service type (`FOREGROUND_SERVICE` plus a declared type, and a
  notification the user must be able to see) that must be justified by the feature they serve, not
  by the fact that background work exists;
* a plain started service already covers what this work can honestly claim.

**What is honestly claimed.** Android stops a background service some minutes after the application
leaves the foreground, and a process that is killed takes detection with it: `START_NOT_STICKY` means
Android will not recreate it on its own. Detection therefore runs while Nivara is in front and for
as long as the platform keeps the service alive afterwards — and *reliable* protection of
applications opened much later is not claimed, not verified, and not promised on any device or OEM.
The verifier refuses `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`
and `BIND_ACCESSIBILITY_SERVICE` until the documentation records why it needs them.

## Overlay decision: carried forward to the protection surface

`SYSTEM_ALERT_WINDOW` was still **not** declared at the end of the detection work, and no overlay capability
was checked. Detection produced an internal requirement and nothing drew above another application,
so the permission would have entitled Nivara to something it did not do yet. The protection surface owns the
capability check, the settings entry point, the explanation and the presentation, and added all of
them together with this documentation and the verifier's allow-list entry.

The distinction stays explicit: Usage Access *observes* which application is in front; overlay
permission *draws* above another application. Neither substitutes for the other, and a granted
overlay permission locks nothing by itself.

## Battery and OEM behaviour

Nothing added, and no reliability promised. Detection is driven by reading usage events while the
process is alive; no exemption is requested, no manufacturer auto-start screen is opened, no
undocumented intent is used. If later work demonstrates on real devices that protection fails
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

Detection adds its own gaps to the:

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
  privileged callback; they matter to the surface that presents a prompt and are recorded here so that
  surface can decide whether a wider window is worth its cost.
* **The storage format is verified on the JVM**, with real files and real atomic writes, but not on
  a device's storage stack.

# The protection surface and authentication flow

Detection sees that a protected application is in front and publishes an internal requirement. This
surface is what turns that requirement into something the user sees, and what carries their answer back
into the session layer that already exists. It adds no new authentication mechanism, no new session
and no per-application unlock: it is the missing link between a decision and the credential chain
built in earlier work.

## Scope

* Deciding whether a surface above another application is genuinely required, and adding the
  smallest capability that makes it possible if it is.
* Reading, explaining and opening the permission for that capability, with every state
  distinguished.
* Drawing the surface: owned by Nivara, secure, private, at most one at a time.
* Routing the user's authentication through `CredentialManager` and `BiometricAuthenticator` into
  `SessionManager`, and doing nothing else with it.
* Behaving correctly when an attempt is cancelled, refused, blocked, timed out, or when the session
  is locked while the surface is up.

Explicitly out of scope: App Lock's settings, search and sorting; hiding applications; a custom
launcher; camouflage; a vault; scheduled locking; battery or OEM workarounds; and visual design,
which later work owns. Everything on that list that touches the protected set will use the
repository introduced in this work.

## Why a surface is required at all

Android offers an application no unprivileged way to put something in front of another application's
UI. The options were examined and each was rejected on its own merits:

| Option | Why it is not used |
| --- | --- |
| A background activity start | Refused by the platform from API 29 onwards for a process the user is not interacting with, unless the same overlay grant exists. Adding it would raise a warning the user cannot resolve, and on modern versions it would simply fail. |
| A foreground service with a notification | A persistent notification is a *notification*, not a blocking surface. It cannot stop the protected application's UI from being used, so it protects nothing; it would also require `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`, none of which the feature needs. |
| An accessibility service | An accessibility service reads the content of every screen on the device. That is a vastly broader capability than drawing one window, it is not needed to draw one window, and it would mean asking the user to grant surveillance to obtain a lock screen. |
| Doing nothing and reporting success | The one genuinely unacceptable option: a missing capability must never be presented as "nothing needs protecting". |

The remaining mechanism is a window of type `TYPE_APPLICATION_OVERLAY`, which is exactly the
capability `SYSTEM_ALERT_WINDOW` grants and nothing more. It is the smallest Android-supported way
to appear above the protected application, and the permission itself locks nothing: a granted
overlay permission without the surface would be an empty entitlement.

## Permission: `android.permission.SYSTEM_ALERT_WINDOW`

| Question | Answer |
| --- | --- |
| Why does it exist? | The protection surface must be drawn above the protected application. Android offers no other unprivileged mechanism that can place a blocking surface there. |
| Which feature requires it? | App Lock's protection surface. Detection alone does not need it, and no other feature of this project draws over another application. |
| How is it granted? | By the user, in Android's own "Display over other apps" screen, reached with `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` and Nivara's own package URI. It is never a runtime permission and `requestPermissions` is never called for it. |
| Does it need to be declared? | Yes — without the declaration the app-op can never be granted and Nivara cannot appear in the overlay settings list. |
| Is it needed on Android 9+? | Yes, on every supported version; `TYPE_APPLICATION_OVERLAY` exists from API 26 and `Settings.canDrawOverlays` has existed since API 23. |
| Does it expose user data by itself? | No. It permits a window to be drawn above other applications; it grants no access to their content, and nothing is read from the application underneath. |

The declaration carries `tools:ignore="ProtectedPermissions"` for the same reason Usage Access does:
the permission is protected on purpose, and only the user may grant it.

The capability is read with `Settings.canDrawOverlays`, which reports the app-op as a boolean and
throws on platforms where the operation cannot be resolved. A `null` or throwing answer becomes
`OverlayCapability.Unavailable`, never `NotGranted`: an unreadable check is not a refusal the user
can fix, and it must not be presented as a normal "off" state. `Granted` is shown as *Granted*,
`NotGranted` as *Not granted*, and `Unavailable` as *Unsupported* — three separate sentences,
because the remedy differs and because collapsing them would tell the user something Nivara does not
know.

`Settings.ACTION_MANAGE_OVERLAY_PERMISSION` is opened with Nivara's own package URI so the list is
filtered to Nivara. On devices where that deep link cannot be resolved the intent is retried without
the URI, and a failure to open either screen becomes a generic message — opening the screen is never
treated as a grant. The state is re-read when the preparation screen is resumed, exactly as Usage
Access is.

The verifier keeps the permission on an explicit allow-list and requires this document to justify
it. The remaining permissions this project deliberately does not hold — `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `BIND_ACCESSIBILITY_SERVICE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `QUERY_ALL_PACKAGES` — stay refused until the documentation records
documents a demonstrated need.

## The surface

The surface is a window owned by Nivara and nothing else:

* It is added with the application context through `WindowManager`, as `TYPE_APPLICATION_OVERLAY`
  with `FLAG_SECURE` and an opaque pixel format. `FLAG_SECURE` means the content of the protection
  surface itself cannot be captured in a screenshot, a screen recording or a mirror of the display —
  the same rule the credential screens already follow.
* It is not exported and launches nothing of its own: it is not an activity, it has no task and no
  navigation, and it exists only while a requirement does.
* It shows no sensitive information. It never names the protected application, never shows its icon
  or its label and never mentions a package name. The user already knows which application they
  opened; what the surface adds is that authentication is required.
* It logs nothing. The App Lock packages contain no logging calls, no cryptography (they neither
  store nor derive credentials) and no network access at all; the verifier enforces all three.
* The input the user types goes straight to the existing credential entry component and is handed to
  `CredentialManager` on submit, which owns and clears it. The surface keeps no copy.

### One surface at a time

There is exactly one window, owned by one component, and its state is an explicit state machine —
`Idle`, `Showing(request)`, `Dismissing(request)` — rather than a set of flags:

* A repeat of the same requirement while it is already `Showing` changes nothing and creates no
  second window.
* A different protected application coming forward while a surface is up reuses the same window and
  updates its request; the window is never rebuilt for a new occasion.
* Every ending — authentication succeeded, the user left the application, detection stopped, the
  service stopped, the preparation screen switched protection off — goes through the same dismissal,
  which is idempotent. A second call to stop an already-stopped host is a no-op.
* If Android removes the window itself (for example the user revoked the permission), the surface
  reports the requirement as one that could not be presented and the presenter publishes
  `Unpresentable` with `OverlayUnavailability.Failed`. It does not re-attach in a loop: the
  requirement stays reported as a failure, and the next occasion — leaving the application and
  coming back — is what tries again. The same holds when the window manager refuses the very first
  attach. A requirement that could not be shown *for want of the capability* is the one case that is
  re-checked on every wake-up, because granting it in Android's settings is possible without leaving
  the application.
* Leaving the surface — the explicit "leave this application" action or the Back gesture — sends the
  user to the device's home screen with `ACTION_MAIN`/`CATEGORY_HOME`. The protected application is
  not opened or bypassed by the surface, and no application-launching intent is used. The surface is
  deliberately **not** hidden at the moment the leave action fires: it stays up until detection sees
  the launcher, so that a home intent the platform refuses can never leave the protected application
  uncovered. The cost is that the surface may remain over the launcher for one detection interval,
  which is the honest side of that trade.
* When detection is running but cannot decide anything (`AppLockState.Unavailable`), a surface that
  is already up stays up. Removing it would turn "Nivara cannot see" into "everything is fine",
  which is the one reading this work must never produce; and no surface is created, because Nivara
  cannot name an application it cannot see.

The window-owning component is behind a small seam (`OverlaySurface`) so the lifecycle rules above
can be tested on the JVM with a fake, and so the platform calls stay in one file. The platform half —
`WindowManager.addView`/`removeView`, `ComposeView` inside an overlay window, Back interception and
the home intent — cannot be exercised on the JVM and is declared as compiled, not verified, below.

## Authentication routing

The surface asks for authentication; it does not implement it. The primary credential is discovered
at the moment the surface is shown by asking `CredentialManager.status()` what is configured — never
hard-coded, never read from a preference and never guessed. The answer is shown through the existing
`CredentialEntry` component, which renders whatever type the credential layer reports.

* **Primary**: `CredentialManager.verify(input)` produces an `AuthenticationOutcome`, which is handed
  to `SessionManager.establish(outcome)`. Every outcome is possible — succeeded, failed with a block
  window, temporarily blocked with a retry delay, not configured, invalid configuration — and every
  one is shown as the credential layer's own message. There is no second password, PIN, pattern or
  verification path.
* **Biometric**: offered only when the existing `BiometricAuthenticator` reports it enabled, through
  the same authenticator and system prompt the credential screens use.
  `BiometricAuthenticationOutcome` reaches the same `SessionManager.establish` overload. Cancelled,
  failed, system-blocked and invalidated outcomes leave the session untouched and keep the primary
  credential available.
* **The platform prompt's host.** Android's biometric prompt must be hosted by one of Nivara's own
  activities. While a protected application is in front, Nivara's activity is stopped, so the
  existing authenticator is asked while it may have no live host — in which case it reports the
  secondary path as unavailable, that outcome is shown, and the primary credential stays available.
  Nothing is fabricated to work around this, and the limitation is recorded rather than hidden.
* **The session**: the one `SessionManager` gate. App Lock adds no unlocked flag, no per-application
  authentication state, no unlocked-package list, no session timer of its own and no authentication
  counter. The only thing it can do to the session is what every other screen does: hand it an
  outcome, and read `currentState()`.
* **Quick Lock and expiry**: `lockNow()` and session expiry are observed through the session's own
  state stream. When the gate says the session is gone while a protected application is still in
  front, the requirement is published again by the same reconciliation that handles every other
  wake-up.

### Request identity and staleness

A requirement is identified by a monotonically increasing in-memory id together with the package
name it was raised for. Every authentication result is used only if it belongs to the request that is
still current:

* An attempt that finishes after the user has moved to another protected application is dropped, so a
  success obtained for one application cannot open the gate for a different situation.
* A→B races cannot unlock B: B's request has a different identity, and A's late result no longer
  matches.
* The identity alone is not enough, and the code does not pretend otherwise. Reconciliation is
  deliberately serialised behind a running attempt — one decision at a time — so a user who moved on
  mid-attempt would still be described by the old request at the moment the result arrives. The
  result is therefore also checked against detection's own current answer, which is not serialised
  behind anything: if detection now says a different application needs authentication, the attempt's
  answer is dropped however the published state reads. If detection cannot answer at all, nothing
  says the user moved and the requirement is still the one on screen, so the attempt stands; if
  protection has been stopped, the occasion is over and the answer opens nothing.
* Admission to an attempt is taken synchronously, before any work is scheduled, so a second tap while
  an attempt is running is refused there and then rather than queued behind the first and started
  when it finishes. The input of a refused attempt is cleared without reaching the credential layer.

The identity lives in memory only, is never persisted and is never written anywhere.

## The preparation screen's protection switch

The App Lock preparation screen gains a switch that starts and stops protection, and a card for the
overlay capability alongside the ones preparation introduced. The switch only calls the component that
owns detection; the state it displays is read back from that component's own state, never assumed
from the tap. A platform refusal — a restricted background start, a component an OEM build has
disabled — is reported as a failure and the switch keeps showing that protection is not running,
because claiming otherwise would be claiming something this layer cannot know. Returning from
Android's overlay screen with the grant in place is confirmed once, and returning without one is not
an error. Its three values — stopped, running, running-without-a-decision — are distinct, because
"detection is on but the platform will not answer" is neither off nor working. Starting is disabled
until every prerequisite is satisfied, and stopping is always available, because turning something
off never needs a permission. A missing prerequisite is never presented as an empty device or as
"nothing to protect".

## What is not verified here

Every platform claim in this chapter is a claim about Android, verified by reading the platform
documentation and by compiling against it — not by running it:

* **No device or emulator was available.** Nothing in this work was executed on Android hardware.
  The instrumented tests that can only run on a device are compiled in CI, which is not the same as
  running them.
* **`Settings.canDrawOverlays` was not executed**, and no grant, revocation or Settings round-trip
  was observed on a device.
* **The window was never attached.** `WindowManager.addView`/`removeView` with
  `TYPE_APPLICATION_OVERLAY`, `FLAG_SECURE`, the insets handling and the display-cutout mode are
  compiled and asserted by an instrumented test that has not run.
* **Compose inside an overlay window was not exercised.** The `ComposeView` lifecycle owners, the
  recomposition of the surface state, the Back interception and the home-screen intent are
  compiled-only.
* **Authentication through the overlay was not performed.** The JVM tests prove routing, request
  identity, staleness, de-duplication, session outcomes and overlay state transitions with fakes; no
  fingerprint, PIN or system prompt was shown by this code on a device, and the own device gaps
  are unchanged.
* **The behaviour of the surface while the process is killed, and across OEM power management, is
  not verified and not claimed.** The service that owns the surface remains stop-and-forget
  (`START_NOT_STICKY`); if the process is gone, nothing is drawing, and no mechanism available to
  Nivara changes that.

# Choosing which applications are protected

## Scope

App Lock already discovers applications, decides what needs authenticating, and presents the
requirement. What was missing is the user's half: seeing the applications the device has, seeing
which of them are protected, and changing that. This work adds one screen — reachable from home,
one tap from where App Lock's state is described — and nothing else. App hiding, a vault, scheduled
locking, camouflage, usage statistics and OEM power workarounds are not part of it and are not
described here.

The screen shows:

* what App Lock currently needs, and whether protection is running (the same aggregate and the same
  component the preparation screen reports);
* every application the device can launch, with the protection the stored set gives it;
* a search field, a section control (all applications or only protected ones) and an ordering
  control;
* one action per row: protect, or stop protecting.

It shows no usage information, no timings and no history, because none is collected.

## The list comes from the existing discovery

Rows are built from `ApplicationRepository`, the same launcher query discovery introduced, with the
same `<queries>` declaration and the same package-name identity rule. A label is what a row prints;
a package name is what it acts on. A search matches what the domain's `ApplicationSearch` contract
already says it matches — the label, or the package name — and nothing else.

An application that disappears between discovery and drawing is not a crash and not a silent
deletion: the row is rebuilt from the next read, and a change aimed at a package that is no longer
in the catalogue is refused with a sentence saying so. Protection already stored for an application
the device no longer has is **kept**, not pruned, and the count of those names is shown rather than
left as a quiet disagreement between the list and the stored set.

## One owner for the protected set

The set of protected applications is stored in exactly one place: the repository detection introduced,
which the detection service reads. This work adds no `SettingsRepository`, no second protected-set
file, no cache of ticked rows and no parallel state in the view model. The screen's list is a
rendering of the last read; every change goes to the repository, and every change is followed by a
fresh read, so the row shows what is stored rather than what was asked for. A repository that
accepted a write and still reports the application as unprotected is drawn as unprotected.

The repository keeps responsibility for the protected set only. Search, ordering, sections, the
icon, the session and the readiness answers live above it, and none of them is a second source of
truth for what is protected.

## What a row is allowed to claim

A row answers "is this application protected right now?", which is not the same question as "is
this package name in the stored set?". The state is derived from two independent answers every time
the list is built — the stored set, and whether the capabilities that present a requirement are in
place:

* **not protected** — the application is not in the stored set;
* **protected** — it is in the stored set, and the capabilities are granted;
* **protected, but unavailable** — it is in the stored set and a capability is missing, so it
  cannot actually be protected at the moment;
* **unknown** — the stored set could not be read, so the row claims neither.

The third value exists because the alternative is a tick that lies: a user who protected an
application and then revoked the overlay grant has done everything Nivara asked and is still not
protected. The fourth exists because an unreadable configuration is not an empty one — the failure
the storage layer is deliberately built to fail closed against. When the set cannot be read, the
list draws no protection claim at all, the count of protected applications is not invented, the
protected section cannot be listed, and a change is refused; nothing about the file is overwritten
because the screen could not read it.

Every state is written out in words next to its control as well as reflected in the control's
label, so no state depends on colour.

## Search

Searching is `ApplicationSearch`, unchanged from discovery: the query is trimmed and matched
case-insensitively against the label and the package name, an empty query returns everything, and a
query that matches nothing is its own answer — not an empty device. Filtering happens in the view
model over the last read; it performs no repository call, writes nothing, uses no network and never
touches the stored protected set. The query is a filter, not a source of truth, and a refresh keeps
it.

## Ordering

Two orderings are offered: the domain's default comparator (label, case-insensitively, with the
package name breaking ties) and its exact reverse. Both come from `ApplicationOrdering`; there is no
second comparison rule and no per-screen sorting logic. Identical labels keep a fixed sequence in
both directions because the whole comparator — including the tie-breaker — is reversed, so the same
device always produces the same list.

There is deliberately no "recently used", "suggested" or frequency-based order. Nivara keeps no
usage history, and a ranking would require either collecting one or inventing one. Sorting is a
rendering decision: it never writes to the repository and never changes what is protected.

## Readiness and prerequisites

The screen reports the same readiness the preparation screen does, and reports it without collapsing
it into one flag:

* **which capabilities are missing**, named individually, from the same aggregate
  (`AppLockSetupState`) and the same `UsageAccessRepository`/`OverlayCapabilityRepository` answers
  discovery and the protection surface introduced. The screen checks no permission itself, and opens no settings
  screen itself: the preparation screen is one tap away and remains the place where a grant is
  explained and requested.
* **whether protection is running**, read from the component that owns protection
  (`AppLockMonitor`), in its own three values — stopped, running, running without a decision.
* **what is stored**, as a count, or as an explicit statement that it could not be read.

A capability that could not be *read* counts as missing, never as granted. Missing capabilities
never turn a protected application into an unprotected one, and never turn a stored configuration
into an empty one.

## Changing the set requires a session

Opening the list needs no authentication. It shows the same applications the device's launcher
already shows to anyone holding the phone, and requiring a password to look at it would add
friction without adding protection.

Changing it does. Unprotecting an application is the one action that can undo App Lock for that
application, and anyone holding an unlocked phone could otherwise remove the application from the
set and then open it. Every change therefore goes through the existing `SessionManager`: the action
is refused while the gate is closed, and the screen offers the existing credential or biometric flow
— the already-built screens — as the way to open it.

This is the whole policy, and it is deliberately not more than this:

* **no App Lock password.** There is one credential in Nivara, and one place that verifies it.
* **no second session.** The screen never creates, extends or holds one. It asks the gate whether it
  is open, and the gate is the same object Quick Lock and the surface already use.
* **no session extension from looking around.** Searching, scrolling, sorting and switching sections
  never touch the session's expiry. The gate closes on its own terms; the screen's controls follow
  it, including while it is open.
* **no per-application unlock state.** Whether an application may be opened is decided by detection
  from the foreground application and the session, exactly as before.

A write is rejected while one is already in flight, so two rapid taps cannot produce two writes, and
a refusal is reported in words rather than in a control that appears to have worked.

## Refresh behaviour

The screen reads when it is created, whenever it is resumed — which is how a return from Android's
own settings is noticed — and after every change. It does not poll, does not restart the detection
service after a change, and does not ask the service to refresh: the service reads the same
repository, so the next observation already sees the new set. A failed refresh keeps the list that
was already drawn; a failure of the first read is the screen's retryable error state, and neither
one touches stored protection.

## Icons

A row draws the application's icon when the device can produce one, through the platform's own
facilities only: the application record and its icon resource, scaled to the size the row needs.
The loader lives in the data layer, never throws, and returns nothing for a package that has
disappeared or an icon that cannot be read; the row then draws a neutral placeholder. Nothing about
an icon is persisted or copied, no image library was added, and the icon is never an identity: the
label is what identifies the row, the icon carries no content description of its own, and the action
acts on the package name.

## Accessibility

The list is usable with touch and with a screen reader: the section and ordering controls are an
exclusive group whose options announce their state, the search field carries a label, each row's
protection state is text rather than a colour, and each row's action describes what it will do to
which application ("Protect Camera", "Stop protecting Camera") rather than only naming a verb. A row
whose state cannot be read says so and offers no action, so nothing can be confirmed that Nivara
cannot confirm.

## What is not verified here

The same boundary applies, and this chapter is honest about it:

* **No device or emulator was available.** Nothing in this work ran on Android. The claims about
  icons, lists and settings are claims about the platform's documented behaviour, asserted by
  compilation.
* **The instrumented tests are compiled, not executed.** The suites that need a device are built by
  CI (`assembleDebugAndroidTest`); a build is not a run.
* **Icons were never rendered.** `applicationInfo`, `loadIcon` and the density conversion are
  compiled, and the missing-icon path is the only path a JVM test can exercise.
* **No permission was granted or revoked**, and no return from Android's settings screen was
  observed; the refresh-on-resume path is covered by JVM tests with fake repositories.
* **Navigation, layout and talkback behaviour were not seen.** The composition of the screen runs
  only when the application is launched on a device.

# App Lock and hidden applications (the hidden-app boundary)

Locking an application and keeping it out of sight are **different decisions**, made on different
screens, stored in different records, and neither one implies the other. All four combinations are
ordinary configuration:

| | protected | not protected |
| --- | --- | --- |
| **hidden** | authentication is required to open it, and it is kept out of Nivara's own launcher | it opens freely, and it is still kept out of Nivara's own launcher |
| **visible** | authentication is required to open it | the default |

The consequences for App Lock are narrow and deliberate:

* **Detection and the protection surface never read the hidden record.** Whether an application is
  in front, whether the protected set requires authentication for it and whether the surface is
  drawn are decided from the protected set, the session and the platform, exactly as detection and the protection surface
  describe. Hiding cannot change what App Lock does, and un-hiding cannot either.
* **Hiding never locks and locking never hides.** A hidden, unprotected application opens freely
  when something else launches it — hiding is not a protection mechanism, and the management screen
  does not offer it as one. A protected, visible application is locked as usual.
* **Two records, two files, two repositories, no shared mutable state.** The protected set
  (`NVPL`, `data/applock`) and the hidden set (`NVHA`, `data/apphide`) share only the low-level byte
  helpers that read and write integers and checksums. Damage to one is never read as the other, and
  neither feature can write the other's file.
* **One session gate, one policy.** Both screens read without a session and change only while the
  existing gate is open, checked through the same `SessionManager.currentState()`. Neither feature
  grows a password, a timeout or a failure counter of its own.

The hidden record, its format, its fail-closed behaviour, the management screen and the contract
Nivara's own launcher will consume are documented in
[`docs/apphide/README.md`](../apphide/README.md).

## Survival: keeping protection alive away from Nivara

Detection only protects while it runs, and the user is inside the protected application exactly
when Nivara is not on screen. The platform stops a plain background service within minutes of
the application leaving the foreground, and always on a restart, so protection is carried by a
foreground service, kept exempt from battery deferral, and restored after a restart. Each piece
is a deliberate, declared permission, and each is granted by the user, never by Nivara:

* **android.permission.FOREGROUND_SERVICE** and
  **android.permission.FOREGROUND_SERVICE_SPECIAL_USE**: the detection service promotes itself
  to the foreground with one quiet, low-importance notification ("App Lock is on"), because no
  standard foreground-service type describes an application lock. The `specialUse` type is
  declared on the service together with the
  `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property stating the use. If the platform
  refuses the promotion, the service degrades to the plain started service rather than taking
  protection down, and the next foreground start promotes it again. The notification permission
  for that one notice is **android.permission.POST_NOTIFICATIONS**, a runtime permission the
  onboarding offers as optional: protection runs without it, Android simply silences the notice.
* **android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS**: the onboarding gate shows
  Android's own "ignore battery optimizations" confirmation for Nivara so the platform does not
  defer the service while it is doing its job. The permission only entitles Nivara to ask; the
  exemption is granted by the user in Android's dialog or settings, and the gate re-reads the
  exemption on every return to the foreground.
* **android.permission.RECEIVE_BOOT_COMPLETED**: a non-exported boot receiver reads the durable
  run-state — one atomic file holding the user's on/off decision, written by the protection
  runner and read by nobody else — and, only when it says "on", starts the service again after
  `ACTION_BOOT_COMPLETED` or `ACTION_MY_PACKAGE_REPLACED`. A missing or corrupt record is
  "off", and a platform refusal at boot is a delay, not a loss: the activity's resume path
  restarts protection from the foreground on the next open.

Together these answer the question the earlier stage deferred — what keeps protection running
when Nivara is gone — without an accessibility service, a device-admin claim, a battery
exemption Nivara grants itself, or any second session gate. The onboarding gate asks for Usage
Access, overlay and the battery exemption before anything else, on every entry, until all three
are granted; once granted it never appears again.
