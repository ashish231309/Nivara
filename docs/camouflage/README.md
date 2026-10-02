# Application identity (camouflage)

This document records what camouflage is, how it is implemented, what it deliberately does not do,
and how a user reaches Nivara whatever name it is currently showing. It is the durable reference for
the feature, not a walkthrough of the screen.

It covers:

* **[Application identity](#application-identity)** — the name and icon Nivara's
  launcher entry presents, and the ordinary route back to Nivara's own identity.

# Application identity

## Purpose

Nivara can present its launcher entry under a benign, ordinary identity — a name and an icon for a
utility nobody looks twice at — instead of its own. The point is the ordinary situation: a phone
lying on a table, someone glancing at the home screen and seeing a notes application rather than an
application whose name announces that it protects things.

Camouflage is presentation, and only presentation. It is **not a security boundary**, and it is not
offered as one:

* the application stays installed under the same package name, `com.nivara.app`;
* it is signed by the same key, holds the same data, and runs the same code;
* Android's Settings, the package manager, ADB, a device administrator, a work-profile
  administrator, a security product and every privileged tool still list it;
* nothing is encrypted, removed, relocated or concealed by it;
* it **does not hide Nivara from Android**. What changes is which name and icon the device's home
  screen shows for the application's launcher entry, and nothing else.

Anyone who opens Nivara's settings — from the launcher entry, from Android's application list, from a
search in Settings, or from the recents screen — meets exactly the same security model as before: the
primary credential, biometric unlock if it was enabled, App Lock and the session timeout, all
unchanged and none of them bypassed.

The feature exists because a harmless-looking icon is sometimes preferable to an obviously
security-shaped one. It is a convenience and a social-privacy measure, and the screen that configures
it says so in as many words.

## Supported identities

Four identities are declared. One of them is Nivara's own, and the other three are the benign ones:

| Identity | Launcher entry shows | Component |
| --- | --- | --- |
| Nivara (the default) | Nivara's own name and icon | `.MainActivity` |
| Notes | the notes identity | `.CamouflageNotes` |
| Calculator | the calculator identity | `.CamouflageCalculator` |
| Weather | the weather identity | `.CamouflageWeather` |

Nivara's own identity is not a "camouflage off" switch: it is one of the four, offered in the same
list, selected in the same way and restored the same way. There is no separate path for turning
camouflage off, because there is nothing separate about it.

The set is deliberately fixed and small. Every identity is an Android component declared in the
manifest with its own name and icon, so choosing one is a single platform decision with no
Nivara-owned state to write, migrate, verify or lose. An open-ended profile framework — a store, a
format, a version, a migration path and a failure mode — would answer a question that four constants
answer completely, and would add a way to be wrong.

## Implementation architecture

```
domain/camouflage/     what an identity is, and the rule that decides which one is being presented
data/camouflage/       the platform: reading and changing the enabled state of Nivara's own entries
ui/camouflage/         the configuration screen, its copy and its state machine
```

* **The identity** (`CamouflageProfile`) is an enumeration: a stable identifier, and the knowledge
  that Nivara's own identity is the default and the others are camouflage. It is Android-free, holds
  no resources and no state, and cannot fail.
* **The rule** (`resolveCamouflageIdentity`) is a pure function over the set of identities whose
  launcher entry is enabled: one enabled entry is the identity the device presents; none, or more
  than one, needs repair. That is the whole of the decision, and it is tested exhaustively without a
  device — including the property that matters: a state Nivara cannot vouch for never becomes a
  camouflage identity, because a name the user did not choose is worse than their own.
* **The platform** (`AndroidCamouflageRepository`) reads the component states and changes one of
  them. It is the only place in the project allowed to touch a component's enabled state, and it only
  ever touches Nivara's own launcher entries — the verifier refuses any other file that mentions the
  call, and refuses any camouflage source that mentions the Home activity.
* **The screen** (`CamouflageScreen`) draws the four identities, marks the one in use, and states what
  the feature does not do and how to get back.

There is **no second launcher, no second activity class, no second navigation graph, no second
authentication and no second store**. The three benign identities are the same activity, presented
differently; the change is made through the existing `SessionManager`, and the screen that offers it
is an ordinary destination in the existing graph.

## Android component behaviour

The mechanism is `activity-alias`, and the shape is the one Android's own launcher honours:

```xml
<activity-alias
    android:name=".CamouflageNotes"
    android:enabled="false"
    android:exported="true"
    android:icon="@mipmap/ic_camouflage_notes"
    android:label="@string/camouflage_profile_notes_label"
    android:targetActivity=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity-alias>
```

Three decisions are worth stating, because each is a guarantee rather than a detail:

* **`android:enabled="false"`.** On a fresh install exactly one launcher entry is enabled — Nivara's
  own — so the shipped default is unambiguous and no normalisation is needed at start-up. This is also
  why the verifier pins the manifest: an alias declared enabled would put a second Nivara entry in the
  launcher, and four of them on a device nobody had configured.
* **`android:targetActivity=".MainActivity"`.** An identity is a name and an icon over the component
  that already starts Nivara. It is not a second screen, not a second task and not a second
  implementation, and no identity can present anything the application did not already have.
* **MainActivity stays enabled, exported and labelled with the application's own name and icon.**
  It is the entry the user can always return to, and the only one they can always recognise.

Selecting an identity enables the entry that carries it and then disables the others. The order
matters: a change interrupted by a crash, a reboot or a kill leaves two entries — which the next read
repairs — rather than none, which would leave the user with no way to open the application at all.
`DONT_KILL_APP` accompanies every change, so choosing an identity does not close the screen the user
is looking at.

Reading is three-valued, and the third value is the interesting one. Android reports a component the
user has never touched as `DEFAULT`, in which case the manifest's declared state is the effective
one — enabled for MainActivity, disabled for every alias. An explicit enable or disable is taken as
it is. `DISABLED_UNTIL_USED` counts as no entry, because it is not one.

Nothing here changes the user's Home selection: no preferred activity is written, no other launcher's
preference is cleared, and Nivara never selects itself as Home. The Home contract the launcher added is
untouched by this feature — the alias is never aimed at the Home activity, and the Home activity's
component state is never changed.

## Persistence design

**There is no Nivara-owned persistence for this feature, and that is the design.**

The selection lives in the platform's own component state. There is no file, no preference store, no
version, no checksum and no migration, which means:

* there is no format that can be corrupted, and no malformed state to interpret;
* there is no "missing state" to resolve, because a device that has never chosen an identity simply
  has every alias disabled by its manifest declaration;
* there is no write that can be interrupted halfway, because a change is one platform call per
  component;
* there is nothing to keep in sync with what the launcher is actually showing — the platform *is*
  what the launcher is showing;
* the identifiers are validated by construction: only the declared identities exist, so an unknown
  identity cannot be selected, and a component that matches no declared identity is dropped rather
  than guessed at.

The one state that *could* be ambiguous is repaired rather than interpreted: nothing enabled, or more
than one thing enabled, re-applies Nivara's own identity. The repair never chooses a camouflage
identity, and the user is never silently shown a name they did not pick.

Nothing secret is stored, because nothing secret is involved. An identity is a name and an icon.
There is no credential, key, biometric material, package name, hidden set, protected set or session
state anywhere in this feature — the verifier refuses a camouflage source that even names the hidden
or protected sets.

## Configuration screen

`CamouflageScreen` — "Application identity" — is an ordinary settings destination, reached from the
home screen's card, exactly like the App Lock and hidden-application screens. It shows:

* the identity currently in use, by name and picture;
* all four identities, each with its icon and a plain button;
* what camouflage does not do, at the same size as the feature itself;
* how to get back to Nivara, whatever it is currently called.

Changing the identity requires a valid session from the existing `SessionManager`. Without one, the
screen offers the existing credential screen and changes nothing; with one, the change is applied and
then **read back**, so the screen shows what the device reports rather than what the tap asked for.
The screen never authenticates anybody, keeps no session of its own, and never falls back to a
credential of its own — there is none to fall back to.

No secret gesture, tap sequence, dialler code or calculator equation reaches this screen or any other:
recovery is an ordinary capability, and the verifier refuses the patterns that would introduce one.

## Recovery path

The recovery path is deliberately the boring one:

1. **From the launcher entry.** Whatever identity is selected, the enabled entry starts Nivara, and
   Nivara opens on its home screen — the settings surface, with the identity card on it. There is
   always exactly one such entry, and it is a platform guarantee rather than a hope: Nivara's own
   entry is enabled unless the user selects another one, at most one entry is ever enabled, and a
   device state that breaks that rule is repaired to Nivara's own identity on the next read.
2. **From Android.** Settings → Apps lists Nivara under `com.nivara.app`. So does the package
   manager, so does a search, and so does the app drawer of any other launcher. That is not a
   limitation of the implementation; it is what camouflage is, and it is why forgetting which name
   was chosen cannot lock anyone out.
3. **From the Home surface.** If Nivara is the device's Home application, its launcher surface has a
   settings route, and that route is the same settings screens.

All three paths arrive at the same place and meet the same gate: reaching Nivara's configuration does
not require a session — the home screen is a settings screen — but changing anything that needs a
session asks for the normal authentication through the credential or biometric flow that the device
already uses.

What camouflage cannot do is strand the user. The failure mode this feature was built to avoid —
*the name was changed, and now Nivara cannot be found* — is prevented in three independent ways: the
identity that is always available is Nivara's own; an ambiguous or empty device state is repaired to
that identity; and Android's own lists are untouched, so the application is always findable by the
name it was installed under.

## Authentication requirements

| Action | Requirement |
| --- | --- |
| Looking at the identity screen | none — it says what the application is called, which is not personal data |
| Changing the identity | a valid session, through the existing credential or biometric flow |
| Restoring Nivara's own identity | the same valid session: it is an ordinary selection |
| Reaching Nivara's settings after camouflage | none — this is a route, not an authorization |
| App Lock, hidden applications, the vault | unchanged, and each still asks for exactly what it asked for before |

The session is the one `SessionManager` holds. Camouflage establishes nothing, extends nothing and
ends nothing; it asks the gate and reports what it says. A session that expires, or that Quick Lock
ends, ends the ability to change an identity with it, and a new process starts with no session at
all — while the identity itself remains, because it is the platform's record and not a session.

## Limitations

* **Not a security boundary.** Camouflage is exactly as strong as a name and a picture are. Anyone
  who opens Android's application list sees Nivara by name, and nothing in this feature slows them
  down by a single step.
* **Not application hiding.** Hiding an application from Nivara's own launcher is a different feature
  with a different store, and camouflaging Nivara neither hides anything nor unhides anything.
* **Not a change to the Home contract.** Nivara's Home entry keeps its own name, so a user who set
  Nivara as Home can always recognise it in the Home picker.
* **The identity is not written into Android's record of the application's name.** The platform
  offers an API for that on Android 13 and later, and it is deliberately not used: the identity is
  presented through a declared component, which keeps the mechanism reversible, inspectable and free
  of any Nivara-owned state. The practical consequence is that some system surfaces keep Nivara's own
  name; the settings screen and this document say so rather than implying otherwise.
* **No per-application camouflage.** Only Nivara's own launcher entry can be presented differently.
  Nivara cannot rename another application, and does not try: component state belonging to another
  application is not something it can change, and the verifier keeps that line explicit.
* **Not for the vault.** Nothing here protects the vault, the credential or App Lock, and nothing in
  those features depends on which name Nivara is showing.

## Relationship to App Lock

None, deliberately. Camouflage changes a name; App Lock protects applications. The four combinations
of hidden and protected are unaffected, the protected set is not read, written or mentioned by any
camouflage source, and the protection surface is not involved. A camouflaged Nivara whose own
protected applications are locked behaves exactly as an un-camouflaged one:

| Condition | Behaviour |
| --- | --- |
| Any identity + a protected application | the existing App Lock flow, unchanged |
| Any identity + Quick Lock | the existing session invalidation, unchanged |
| Any identity + an expired session | the existing authentication, unchanged |
| Camouflaged + protected + hidden | all three features behave as they always do, independently |

Camouflage adds no unlock of its own, and there is no state it could add one to: it holds no session,
no credential and no protection state.

## Relationship to hidden applications

Separate in both directions, and the verifier enforces both:

* no camouflage source reads or writes the hidden set, and none of them even names its types;
* no hidden-application source reads the identity, so unhiding an application, hiding one, or
  changing what is hidden cannot move the name or the icon Nivara presents under;
* selecting an identity never hides or unhides anything, and hiding an application never changes an
  identity.

Nivara does not hide itself, automatically or otherwise. It is not in the hidden set, it cannot be
added to it by this feature, and Android's installed-application state is not touched by any of this.

## Verification status

Executed without a device (JVM, on CI):

* the identity model: the declared identities, their identifiers, the default, and the separation
  between Nivara's own identity and the benign ones;
* the resolution rule: every subset of enabled entries, the fail-closed cases, determinism, totality,
  and the property that an unverifiable device state never becomes a camouflage identity;
* the presentation mapping: every identity has a name, a glyph and a colour, distinct from each other
  and from Nivara's own;
* the configuration screen's state machine: what is drawn, what a change requires, what a refused
  change does (and does not) report, what a closed gate does, that one change is one change, and that
  the screen never opens, extends or ends a session;
* independence on real files: an identity change leaves the hidden and protected sets byte-for-byte
  unchanged, creates no file where there was none, and neither of those features can move the
  identity.

Compiled but **not executed** (no device or emulator exists here): the two instrumented suites — the
configuration screen's composition, and the components as the platform sees them (one enabled
launcher entry on a fresh install, three declared-but-disabled aliases, the Home contract intact, no
alias aimed at the Home entry).

Not verified at all, and not claimed: that a device actually re-draws the launcher entry after a
change, how long a launcher takes to notice, that a particular launcher honours an `activity-alias`
label, how a device with a work profile or a managed device renders the entry, TalkBack, and the
behaviour of a real session expiry or Quick Lock while an identity is being chosen.

The repository checker enforces the architectural invariants this document describes: the declared
identities and the manifest components that must match them, the disabled-by-default aliases, the
enabled-and-exported own entry, the Home contract left alone, component state changed only in the
identity implementation and only for Nivara's own entries, no application-level enable or label
rewrite anywhere, no store, cryptography, logging, scanning or package-visibility expansion in the
identity sources, no second session or credential type, the limitation and recovery copy actually
drawn, the recovery route reachable from the home screen, the two-way independence from the
hidden-application feature, and the absence of any dialler-code or secret-sequence entry. Every one
of those rules is negative-tested by injecting the violation it forbids and confirming that the
checker fails.
