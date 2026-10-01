# App Lock: discovery, Usage Access and preparation

This document records the platform decisions behind the App Lock preparation screen: what Nivara
asks the system, what it declares in the manifest, and what it deliberately does not do. It is the
durable reference for the stages that follow, not a description of the UI.

## Scope of this stage

The preparation screen answers three questions and nothing more:

1. Which applications can the user open on this device?
2. Has the user granted Nivara Usage Access?
3. What is missing, and where does the user change it?

App Lock itself — choosing applications, detecting the foreground application, drawing an
authentication prompt over another app — is a later stage. Nothing here blocks, hides, monitors or
records an application, and nothing here reads usage history.

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

## What is not verified here

Discovery, the Usage Access app-op check and the settings intent have not been executed on a
physical device or emulator in this stage. Compilation in CI is not device verification. The JVM
tests cover the Android-free logic — the model and its identity rule, ordering, search matching,
the permission-state aggregation and the mode-to-status mapping — and the instrumented tests that
exercise the platform are compiled, not run, unless a device is attached. In particular, whether a
particular device's Usage Access screen is reachable and whether its app-op reports `MODE_ALLOWED`
after a grant remain unverified until they are observed on hardware.
