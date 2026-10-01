# Nivara's launcher: the Home contract and the app drawer

This document records the decisions behind Nivara's launcher: what it is as an Android component,
what it draws, what it refuses to draw, how an application is opened, who may see hidden
applications, and what is deliberately not true about it. It is the durable reference for the stages
that follow, not a walkthrough of the screen.

It covers one stage:

* **[Stage 11: the launcher and the app drawer](#stage-11-the-launcher-and-the-app-drawer)** — Nivara
  as the device's Home application, and what its drawer does with the hidden set Stage 10 records.

# Stage 11: the launcher and the app drawer

## Scope of this stage

The launcher is the surface where Stage 10's hidden set becomes meaningful inside Nivara: the drawer
is built from the applications the device can launch, minus the ones marked hidden, with an explicit
authenticated way to show them for a while.

What the stage delivers:

* a Home activity Android accepts and starts as the device's launcher;
* a home surface with three things on it — the drawer, Nivara's settings, and the control that shows
  hidden applications;
* an app drawer built from the existing discovery catalogue, with search and the two established
  orderings;
* application launching through the platform's own launcher facilities, by exact package name;
* a fail-closed answer when the hidden set cannot be read;
* a temporary, session-bound reveal that changes nothing that is stored.

What it deliberately does not do:

* it does not make itself the default Home application and does not change any system setting;
* it does not hide anything from Android. Hidden applications stay installed, stay in Android's
  launcher, stay visible in Settings and the package manager, and nothing about them is disabled;
* it does not add camouflage, a fake name or icon, a secret entry sequence, a calculator or dialer
  identity: those belong to Stage 12 and none of them exists here;
* it does not add a launcher-specific password, session, timeout or failure counter;
* it does not add widgets, folders, wallpapers, animations, gestures or the final visual design;
* it does not add a permission of any kind.

## The Home activity

`LauncherActivity` is a normal `FragmentActivity` that answers the standard Home intent:

| Concern | Decision |
| --- | --- |
| Intent filter | `ACTION_MAIN` with `CATEGORY_HOME` and `CATEGORY_DEFAULT`, and nothing else |
| Exported | `android:exported="true"` — the only component exported for anything but the application's own entry point |
| Why exported | Android cannot start a Home application it is not allowed to start from outside the process |
| Other entry points | None. No custom action, no data URI, no `Intent` extra, no activity permission |
| Task behaviour | `singleTask`, `excludeFromRecents="true"`: the launcher is the home task, not a recent item |
| Not the app's own entry | `MainActivity` remains the application's launcher entry; the two are separate components on purpose |

The distinction matters. Every installed application has an exported entry point Android starts when
the user taps its icon, and that is `MainActivity`, unchanged. The Home activity is the second and only
other exported component, and its one capability is "be the home screen" — it cannot be asked to do
anything else.

Any caller able to start `LauncherActivity` can do only what pressing Home does. It cannot ask it to
launch an application, cannot ask it to reveal a hidden one, and can pass nothing that changes what is
drawn: nothing from the intent is read at all.

## Home selection

Nivara appears in Android's Home settings under its application name, and the user selects it there.
Nothing in Nivara:

* adds itself as a preferred activity (`addPreferredActivity` and its relatives are banned project-wide
  by the verifier);
* clears another launcher's preference;
* prompts, nags or short-circuits the system's selection;
* assumes it will stay selected. The launcher is an ordinary activity: if the user picks a different
  Home application, this one is simply not started, and nothing in Nivara behaves differently.

**Runtime verification status:** no device or emulator was available, so Nivara has never actually been
selected as a Home application. What is verified is the manifest contract (statically, and by a
verifier rule), the resolution of the Home intent by the platform (an instrumented test that is
compiled but not executed), and the activity's own composition (a compiled-only instrumented test).
The claim "Nivara can be selected as the launcher" is a claim about the documented platform contract,
not something that was observed.

## Application discovery

The drawer draws the catalogue Stage 6 already produces:

```
ApplicationRepository.installedApplications()   one launcher-intent query, Nivara excluded
                    ↓
            LauncherCatalogue rule              minus the hidden set, in the domain's order
                    ↓
              the drawer's cells
```

* No second scanner: `queryIntentActivities`, `getInstalledApplications` and `getInstalledPackages`
  are banned in the launcher sources by the verifier.
* No new package visibility: the existing `<queries>` element (MAIN + LAUNCHER) is what makes the
  catalogue visible on API 30+, and `QUERY_ALL_PACKAGES` remains refused.
* No accessibility service, no usage history, no caching of the catalogue: it is read on creation, on
  resume, and when the user retries.

## Hidden filtering

```
discovered applications + HiddenApplicationsRead → what the drawer may draw
```

The rule is `launcherCatalogue(...)` in `domain/launcher`, a pure function:

| Hidden set | Drawer |
| --- | --- |
| `Available(set)` | the discovered applications minus the set, in the domain's order |
| `Unreadable` | nothing at all |
| `Unavailable` | nothing at all |
| `Available` + reveal active | every discovered application, with the hidden ones included |

Hidden applications are matched by exact package name — the only identity the stored set has. A stored
name that is not installed changes nothing: it neither appears in the drawer nor causes an entry to be
invented, and because the rule only reads, a discovery gap can never unhide anything.

Protecting an application changes nothing here, and hiding an application changes nothing about
protection. Both dimensions are read from their own repositories during the same load.

## Fail-closed behaviour

This is the security-critical part of the stage, and it is a property of the **view model**, not of a
view: when the hidden set cannot be read, the launcher produces no `Ready` state at all, so there is
no list for a screen to draw by accident.

| Situation | Launcher state | What is on screen |
| --- | --- | --- |
| Hidden set read | `Ready` | the drawer, the reveal control, the settings route |
| Hidden set undecodable | `HiddenStateUnreadable` | an explanation, a retry, and the settings route — no application, no drawer button |
| Hidden set unreachable | `HiddenStateUnavailable` | as above, in its own words |
| Discovery failed before anything was drawn | `DiscoveryUnavailable` | an explanation, a retry, and the settings route |
| Discovery failed after a read | `Ready` | the previous list is kept and a refresh notice is shown — a failed refresh is not evidence that the device changed |
| Hidden set becomes unreadable after a read | `HiddenStateUnreadable` | the list is **taken away**: a drawer that can no longer be filtered is not a drawer |

Three empty-looking situations stay distinguishable, because they mean different things:
`DeviceHasNoApplications`, `AllApplicationsHidden` and `NoSearchResults` (plus `NothingHidden` for the
hidden section). None of them is ever "the hidden set could not be read" — that case cannot reach the
drawer at all.

The launcher never repairs, rewrites or reinterprets a hidden set it could not read. A corrupted file
is reported as corrupt, and it is left exactly as it was found.

## The drawer

* A grid of cells: the application's icon when the device can produce one, its label, and the whole
  cell as the tap target.
* The package name is the identity the tap carries and is never drawn.
* The icon is presentation and never the identity; a missing or unreadable icon falls back to the
  placeholder, and the cell still works.
* Search uses the existing `ApplicationSearch` (trimmed, case-insensitive, label and package name,
  empty means everything). It writes nothing and re-reads nothing.
* Ordering uses the existing `ApplicationOrdering` — name A–Z or Z–A from the same comparator, with
  the package name as the tie-breaker, so the sequence is total and stable.
* The hidden section appears only while a reveal is active: it lists the hidden applications and
  disappears, along with the section itself, the moment the reveal ends.
* No ranking, no recency, no "recently used", no learning: an application's position never depends on
  what the user did with it.

## Launching an application

```
tap → the row's package name → ApplicationLauncher.launch(packageName)
                            → PackageManager.getLaunchIntentForPackage
                            → startActivity in a new task
```

* The package name is the one discovery produced; the launcher cannot be asked to open "the third
  application", a label, or text from anywhere.
* No intent is composed by the launcher, and no component name is sent.
* Nothing is changed about the application being opened — no enabled state, no component state, no
  manifest, no package-manager record.
* No permission is needed or held for this: starting another application's launcher activity is not a
  privileged operation.
* A row whose application has disappeared is checked against the catalogue before the platform is
  asked, so a stale tap re-reads the device instead of sending a start for something that is gone.
* Launching is not treated as a change to the launcher's own data: the drawer is not rebuilt and
  nothing is re-read, because opening an application alters neither the catalogue nor the hidden set.

## Showing hidden applications

The reveal is an ordinary, labelled control on the home surface, with the count beside it:

* it is a button that says what it does, not a gesture, not a hidden sequence, and not a camouflage of
  Nivara's identity;
* it needs a valid session. Without one, the same control routes to the existing credential screen and
  nothing is revealed;
* it is available whether or not a credential is configured: revealing is gated on the session, and a
  user who wants Nivara's own launcher to be complete should not have to enrol anything. This is
  deliberate and is the opposite of a security weakness — with no credential there is nothing for a
  session to authorize, and hiding is a preference rather than a protection mechanism;
* the window is marked sensitive (the project's single `SecureScreenEffect`) while hidden applications
  are actually on screen, and not otherwise.

### Why an in-memory reveal is safe

The reveal is one boolean in the view model:

| Property | How it holds |
| --- | --- |
| In memory only | it is a field of a view model; nothing writes it anywhere |
| Session-bound | it is cleared whenever the gate reports an unauthenticated state |
| Cleared by Quick Lock | Quick Lock closes the gate; the same observer clears the reveal |
| Cleared by expiry | expiry publishes an unauthenticated state, with no timer of the launcher's own |
| Absent after process recreation | a new process has a new view model and an unauthenticated session |
| Never an implicit unhide | the repository is not called; the stored set is unchanged throughout |

The stored set answers *which applications are configured as hidden?*; the launcher answers *which of
them is this authenticated user allowed to see right now?* The two are different questions, and only
the first is written down.

## The session

The launcher uses the existing `SessionManager` and nothing else:

* reading the drawer needs no session — it is the same information the device's own launcher shows;
* revealing hidden applications needs one, checked through `currentState()` at the moment of the
  request;
* the launcher never establishes a session, never extends one and never ends one. It has no timer, no
  failure counter, no password and no session state of its own;
* **resuming the launcher does not extend the session.** Coming back from a launched application
  re-reads the device and the hidden set and re-evaluates the gate; it does not refresh the session,
  which preserves Stage 5's rule that the session begins with an authentication and nothing else.

## App Lock interaction

The four combinations are ordinary configuration, and the launcher respects all of them:

| Hidden | Protected | Launcher behaviour |
| --- | --- | --- |
| no | no | drawn, opens freely |
| no | yes | drawn; opening it raises the existing App Lock surface, which authenticates as it always has |
| yes | no | withheld until an authenticated reveal; then it opens freely, because hiding is not locking |
| yes | yes | withheld until an authenticated reveal; then opening it raises the existing App Lock surface |

The launcher cannot reach the protected set: it is not a dependency of the view model, and the
verifier refuses any reference to `ProtectedApplication` from the launcher sources. Nothing the
launcher does can unprotect an application, and nothing about App Lock changes because Nivara's
launcher is in front.

## Lifecycle and navigation

* The activity is an ordinary activity: `onCreate`, `onStart`, `onResume`, `onPause`, `onStop` and
  recreation are Android's, with no interception.
* Nothing is persisted in `onSaveInstanceState` or anywhere else; the view model holds the reveal, and
  a recreated process is unauthenticated, so hidden applications are hidden again with no marker to
  restore.
* Resuming re-reads the catalogue and the hidden set and re-evaluates the session.
* Back is not trapped. Inside the drawer, Back closes the drawer; at the home surface Back is left
  alone, so the activity behaves like any other and the user can always leave.
* Home and Recents are Android's: the launcher does not intercept the Home key, does not override
  system navigation and does not try to keep itself in front.
* Settings are reached through the existing navigation graph — the same screens and the same routes as
  the rest of Nivara, entered at the launcher destination — so there is no second copy of any settings
  screen.

## Security and privacy

* **No new permission.** Displaying applications, opening them, being Home and filtering hidden ones
  need none. The manifest still declares exactly the two Stage 8 capabilities, both justified by App
  Lock.
* **Nothing new is exported.** One Home activity, with one intent filter; the verifier refuses any
  other exported component that is not the application's own launcher entry.
* **No storage, no parsing, no logging.** The launcher sources may not name storage, a codec, a file
  name, a codec path or a log call; the verifier enforces it.
* **No network, no analytics, no usage history.**
* **No device state is changed.** No component is disabled, no process is killed, no launcher
  preference is written.
* **What hiding is not.** Hidden applications remain installed, launchable by any other means, and
  visible to Android's launcher, Settings, the package manager and anything privileged. Nivara's own
  drawer leaving them out is the whole of the guarantee, and both the home surface and the
  documentation say so.

## Verification status

Executed without a device (JVM, on CI):

* the filtering rule in every combination, including both fail-closed outcomes and a reveal;
* the filtering rule against the **production** hidden and protected stores, on real files, including
  damage, unreachable storage, and the four protected/hidden combinations;
* the launcher's state machine: what is drawn, what is withheld, the four empty states, the reveal
  policy (no session, valid session, expired session, Quick Lock, and a screen that never opens or
  extends one), launch-by-exact-package, stale rows, and that nothing in the launcher ever writes
  hidden state.

Compiled but **not executed** (no device or emulator exists here): the instrumented suites for the
launcher's composition and for the Home contract — Home-intent resolution, the exported surface
holding only the application's own entry point and the Home activity, the activity starting, the
drawer drawing labels, a tap being reported.

Not verified at all, and not claimed: that a device actually selected Nivara as Home, that Android
resolved and started it as the launcher, that an external application was opened, that icons were
rendered, that the activity lifecycle behaved under real pressure, TalkBack, or a real session expiry
and Quick Lock on Android.

The repository checker enforces the rules of this document: exactly one Home activity, exported, with
the Home intent and nothing else; every other exported activity being the application's own launcher
entry; services, providers and receivers private; no `QUERY_ALL_PACKAGES`, no
accessibility service, no device administrator; no component disabling and no programmatic launcher
selection anywhere; the launcher's sources free of storage, codecs, second scanners, cryptography,
logging and session types; the hidden-application contract as a typed dependency of the launcher's
view model; the filtering rule in `domain/launcher` handling both fail-closed outcomes; the launcher
screen applying `SecureScreenEffect()`; the Home activity's class existing where the manifest says it
does; and `docs/launcher/README.md` continuing to exist. Each rule is negative-tested by introducing
the violation it forbids and confirming that the checker fails.
