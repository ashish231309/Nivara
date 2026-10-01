# Hidden applications: the record and the screen that manages it

This document records the decisions behind hidden-application management: what Nivara stores, what
it refuses to store, what the management screen may claim, who may change the record, and what a
launcher will be allowed to do with it. It is the durable reference for the stage that follows, not
a walkthrough of the screen.

It covers one stage:

* **Stage 10: hidden applications and their management** — the record of which applications the user
  wants kept out of sight, the repository that owns it, and the screen that shows and changes it.

# Stage 10: hidden applications and their management

## Scope of this stage

The stage answers one question — *which applications did the user ask Nivara to keep out of sight?* —
and gives the user a screen to answer it on:

* the applications the device can launch, with what the record says about each of them;
* hide and unhide, one application at a time, by exact package name;
* search and ordering over the same catalogue the App Lock screen uses;
* one stored record, owned by one repository, that Nivara's own launcher will read later.

What the stage deliberately does not do:

* it does not change anything on the device — no application's components, enabled state or
  package-manager record is touched;
* it does not hide anything from Android's launcher, and it does not claim to: the platform offers
  no unprivileged way to remove another application's launcher entry, and a feature that pretended
  otherwise would be lying to the person using it;
* it does not add a launcher, an app drawer, a home screen, icons, a grid or a filter;
* it does not add camouflage, fake names or icons, or a secret entry point;
* it does not add scheduled hiding, usage or data statistics, device administration, an accessibility
  service, or any battery or OEM workaround;
* it does not add a second authentication or session mechanism of any kind.

## What "hidden" means here

Hiding is **Nivara's own preference**. Nivara records that the user does not want to see an
application in Nivara's own launcher, and it is that launcher — a later stage — that will act on the
record. Every application in the record stays installed, stays launchable, keeps its data, and keeps
appearing in Android's launcher, in Settings, in the package manager, in a storage analyser and to
anything privileged enough to ask.

Two consequences are stated in the product itself rather than only in this document:

* the management screen says, above the list, that Android's launcher still shows these applications
  and that nothing on the device is changed;
* the home screen's card for the feature says the same thing before the user opens it.

An application that is hidden is therefore **not** a secret. The record is visibility control inside
Nivara's own launcher architecture, not concealment from the operating system, and nothing in the
design or the copy suggests otherwise.

## The domain model

```
HiddenApplication(packageName)        one application, identified by package name
ApplicationVisibility{Hidden,Visible} what the record says about it
HiddenApplicationsRead                the three outcomes of asking
HiddenApplicationRepository           the one owner of the record
```

* **Identity is the package name, and nothing else.** Not the label, not the icon, not the activity
  name, not the launcher title. Two applications can share a label; a label can be changed at any
  time by the user or by the application. `HiddenApplication` therefore has exactly one field, and it
  compares and hashes by it.
* **Names are validated, not trusted.** A stored name must be a usable package name — non-empty,
  no more than 255 characters, dot-separated segments of letters, digits and underscores. A name
  that cannot identify an application never becomes a `HiddenApplication`, and a malformed string
  can never arrive from the screen: the screen acts on a row that came from discovery, and the rows
  hold exactly the names the platform reported.
* **`ApplicationVisibility` has two values, not three.** There is no "unknown" member, because
  "unknown" is the *absence* of an answer: the read returns no visibility at all, and a row that has
  none may claim neither Hidden nor Visible. Modelling it as a third value would have made it too
  easy to draw a row that quietly looks visible.
* **The domain layer has no Android types.** No `ApplicationInfo`, `PackageInfo`, `Drawable`,
  `Context` or `Intent` appears in `domain/apphide`, and the repository verifier enforces it.

## The repository contract

`HiddenApplicationRepository` is the single owner of the stored record:

```
hiddenApplications(): HiddenApplicationsRead   the three outcomes below
hide(application): NivaraResult<Unit>          idempotent
unhide(application): NivaraResult<Unit>        idempotent
```

There is exactly one implementation (`FileHiddenApplicationRepository`), and exactly one property in
the composition root that exposes it. Both the management screen and, later, the launcher depend on
this contract. There is no second store, no cache, no settings copy and no UI-held list: a second
answer to "what is hidden?" would disagree with the first invisibly, and the disagreement would
surface as an application that the user believed was hidden.

### The three outcomes of asking

| Outcome | Meaning | What a caller may claim |
| --- | --- | --- |
| `Available(hidden)` | The record was read. It may legitimately hold nothing. | Every application's visibility, and the count |
| `Unreadable` | The record exists but cannot be decoded. | Nothing at all |
| `Unavailable` | The record could not be reached. | Nothing at all |

The two failure cases are separate because they are different facts — damage to a file that is
there, and storage that could not be queried — and the screen says which one happened. Neither is
ever collapsed into the first: reading a damaged record as "nothing is hidden" would put every
application the user had hidden back in front of them, which is the single most damaging thing this
feature could do.

This is enforced structurally, not by convention: `HiddenApplicationsRead` is a sealed type whose
only ways to answer for an application are `visibilityOf(name)`, `hiddenCount` and `isAvailable`,
which return `null` in the failure cases. A consumer cannot handle two cases out of three.

## The stored format

One file in the application's private storage, `apphide/hidden-applications.nvh`:

```
offset   size   field
------   ----   ------------------------------------------------
0        4      magic "NVHA"
4        1      format version (1)
5        4      entry count, big-endian unsigned
9        ...    entries: 1-byte UTF-8 length, then that many bytes
...      4      CRC-32 over everything before it, big-endian
```

* **Package names only.** No label, no icon, no activity, no timestamp, no ordering hint, no UI
  state, and nothing derived from the current session.
* **Deterministic.** Entries are sorted and de-duplicated before writing, so the same set always
  produces the same bytes, whatever order it was built in.
* **Bounded.** At most 10,000 entries. A count beyond that is refused on write and rejected on read
  before anything is allocated, so a damaged count cannot become a large allocation.
* **Strict on read.** Any of the following makes the whole file unreadable: too few bytes, a bad
  magic, an unknown version, a truncated entry, trailing bytes, a broken checksum, a count that does
  not match the entries, a name that is not a usable package name, or a duplicate entry. Decoding
  either returns the set or `null`; there is no partial result.
* **Written atomically.** The complete file is written to a temporary file and replaced, through the
  same `AtomicFiles` helper the rest of Nivara uses, on the IO dispatcher. A reader never sees a
  half-written file, and an interrupted write leaves the previous record in place.
* **Serialised mutations.** A mutex wraps the read-modify-write cycle, so two concurrent hides cannot
  lose each other's edit.
* **An absent file means nothing is hidden.** That is the only case in which "nothing is hidden" is
  inferred — and reading never creates the file.

### Why a separate format rather than the App Lock file

The protected set's file (`NVPL`, `data/applock`) and the hidden set's file (`NVHA`, `data/apphide`)
share a shape — magic, version, count, length-prefixed names, checksum — and the low-level byte
helpers that read and write integers and checksums. They share nothing else: different files,
different magics, different codecs, different repositories, and no mutable state in common.

Two consequences are deliberate:

* neither file can be read as the other, which is asserted by tests with a real file from each
  feature;
* damage to one feature's file cannot affect the other's record, which is asserted the same way.

A single shared repository with a discriminator was rejected: the two sets have different meanings,
different failure messages and different consumers, and collapsing them would make one feature's
corruption the other feature's problem.

## Fail-closed behaviour

| Situation | Read | Change | What the user sees |
| --- | --- | --- | --- |
| No file yet | `Available(empty)` | Allowed | Nothing is hidden |
| A valid file | `Available(set)` | Allowed | Each row's real state, and the counts |
| The file is damaged | `Unreadable` | Refused, file untouched | "Nivara cannot read which applications are hidden … Nothing has been changed or removed" |
| Storage is unreachable | `Unavailable` | Refused | The same sentence, naming unreachable storage |
| A write fails | Previous record stays authoritative | Reported as not stored | "Nivara could not store that change. Nothing was altered" |
| Discovery fails with a list already on screen | The last list stays; the record is untouched | Refused | A refresh notice, and nothing about hiding changes |
| Discovery fails with nothing on screen | — | — | The retryable error state |

Two rules are worth stating separately, because they are the ones a careless implementation would
break:

* **No optimistic claim.** A change is never drawn because it was requested. After every write —
  successful or not — the records are read again, and the list shows what they say. A write that
  succeeds but leaves the set unchanged is drawn as unchanged.
* **No destructive guess.** A change is refused before anything is written whenever the record could
  not be read, and the file is left exactly as it was found. Nothing in the feature ever repairs,
  truncates or replaces a record it could not understand.

## Package validation before a change

A hide or unhide carries the exact package name of a row that came from discovery. Immediately
before writing, the name is checked against the catalogue that was read:

* if it is there, the change proceeds;
* if it is not, the change is refused and the list is re-read, so a row for an application that has
  just been uninstalled disappears;
* an application that is merely *undiscovered* for a moment is not pruned: its stored entry stays,
  the summary counts it as stored-but-not-installed, and it is hidden again if it returns. A
  discovery gap must never delete the user's configuration, and a refusal to write must never
  become a deletion.

## The catalogue and its independence from App Lock

The list is composed at read time from two sources, and neither is persisted as part of the other:

```
ApplicationRepository  →  what the device can launch        (Stage 6, unchanged)
HiddenApplicationRepository →  what the user hid            (this stage)
                    ↘         ↙
              ManagedHiddenApplication (row: application + visibility)
```

`InstalledApplication` stays a discovery result and does not become a persistence object. The two
dimensions are orthogonal and all four combinations are valid configuration, which the tests assert
side by side with real files:

| | protected | not protected |
| --- | --- | --- |
| **hidden** | locked and out of sight | out of sight, opens freely |
| **visible** | locked, on screen | ordinary |

Nothing in hiding reads, writes or depends on the protected set, and nothing in Stage 7's detection
or Stage 8's protection flow reads the hidden set. The repository verifier refuses any reference
from `domain/apphide`, `data/apphide` or `ui/apphide` to the App Lock types.

## The management screen

A destination of its own (`apphide`), reached from a card on the home screen, added to the existing
navigation graph rather than opening a second activity.

* **Rows.** Icon (when the platform provides one), label, the state in words — "Hidden" or
  "Visible" — and a button that says what it will do ("Hide" or "Unhide"). The button's accessible
  description names the application ("Hide Camera"), so a screen reader announces both the action
  and its object.
* **Package names are not displayed.** They are identities and search inputs, not something a person
  needs to read on a screen.
* **Sections.** "All applications" and "Hidden applications". The hidden section is derived from the
  record alone, and while the record cannot be read it lists nothing and explains why, rather than
  appearing empty as if nothing were hidden.
* **Search.** Through the existing `ApplicationSearch`: trimmed, case-insensitive substring matching
  over the label and the package name, empty query means everything, and nothing is written or
  re-read.
* **Ordering.** Through the existing `ApplicationOrdering`: name A–Z or Z–A, both produced by the
  same comparator, with the package name as the tie-breaker so the sequence is total and stable.
  Ordering is presentation only and never touches the record.
* **States.** Loading, ready, and a retryable error when discovery failed before anything was shown.
  A discovery failure *after* a successful read keeps the list and reports the failure: a failed
  refresh is not evidence that the device changed.
* **Empty states are distinguished.** No launchable applications; no search results; nothing hidden
  yet; the record cannot be read; the record cannot be reached. Each is its own sentence, and only
  the first three are claims about the device.
* **Screenshot protection.** The route applies the project's single `SecureScreenEffect()`; there is
  no second implementation.
* **Accessibility.** Every state is written out; the section and ordering controls are radio groups,
  so their state is exposed without a custom action; nothing is communicated by colour alone.
* **No bypass.** The screen is a composable inside `NivaraNavHost`, its state lives in a route-level
  view model, and the composable itself is stateless and reports taps upwards.

## The hide and unhide flow

```
tap → view model → session gate open?
                 → the record readable?
                 → is the package still in the catalogue?
                 → repository.hide/unhide(exact package name)
                 → re-read the catalogue and the record
                 → draw what they say
```

* At most one change is in flight per screen; the controls are disabled while one runs, and a second
  tap is ignored rather than queued.
* Two refusals happen before any write: the session gate (below) and a record that cannot be read.
  A third happens when the row is stale. Each is reported with its own message, and none of them
  writes anything.
* Repeated hides and repeated unhides are idempotent at both levels: the screen allows the tap, and
  the repository treats an unchanged set as a success without rewriting the file.
* The view model never writes a file, never sees a path and never constructs a codec; it calls the
  repository and renders the result of reading it back.

## Configuration authentication policy

Hiding and unhiding are configuration changes, and configuration changes in Nivara follow the rule
the App Lock settings screen established:

* **Reading requires no session.** The list of launchable applications is personal data the device's
  own launcher already shows to anyone holding the phone, and the record's contents are not a
  secret. Making the list require authentication would train people to authenticate for something
  that protects nothing.
* **Changing requires a valid session**, obtained through the existing credential or biometric
  flows, checked through the existing `SessionManager.currentState()` at the moment of the change.
  When the gate is closed, the screen says so, disables the controls, and offers the existing
  credential screen — it does not authenticate anybody itself.
* **An expired session authorizes nothing**, and Quick Lock closes the gate for changes immediately:
  a change attempted a moment later is refused.
* **There is no hidden-app password, PIN, pattern, biometric key, timeout or failure counter**, and
  no hidden-app session state of any kind. The repository verifier refuses a declaration in this
  feature whose name mentions a session, an unlock or an authentication, and refuses a file that
  reads `SessionManager` without going through `currentState()`.

## Concurrency

One change at a time per screen (a busy flag, the smallest mechanism that makes the rule true), and
serialised writes in the repository underneath it (a mutex around the read-modify-write cycle). Two
concurrent hides cannot lose each other's entry, which the repository's JVM tests assert directly
with two dozen concurrent changes. There is no transaction manager, no queue and no retry policy:
neither is needed for a set of names, and both would be more moving parts than the problem has.

## What Nivara's launcher will do with this (the Stage 11 boundary)

When Nivara's own launcher arrives, it consumes the repository contract:

* it depends on `HiddenApplicationRepository` and reads `hiddenApplications()` — it never parses the
  file, never receives its path, and never learns the format;
* it treats `Unreadable` and `Unavailable` as their own outcomes. A launcher that showed everything
  because the record could not be read would expose exactly the applications the user asked it to
  keep back;
* it filters on `visibilityOf(packageName)` and changes nothing on the device: an application it does
  not show is still installed, launchable by other means, and untouched;
* it keeps no hidden list of its own.

Stage 11 may only relax these rules by changing this document first. The format itself — the magic,
the version, the entries and the checksum — is an implementation detail of `data/apphide` and is not
part of any contract.

## Security and privacy

* **No cryptography is claimed or needed.** The record is not encrypted, obfuscated or otherwise
  disguised, and this document does not describe it as secret. Hiding is visibility control, not
  concealment from Android, from the package manager, from a storage analyser or from anything
  privileged. Encrypting names that Android's own package manager will happily list would add a
  key-management problem and a false sense of secrecy, and nothing more.
* **No credential material.** No password, verifier, key, token or biometric artefact passes through
  this feature, and no cryptography appears in it at all.
* **No logging and no transmission.** Not a package name, not a file name, not an exception. There is
  no network code in the feature, no analytics and no usage history.
* **No new permission.** Reading the launcher catalogue uses the `<queries>` signature Stage 6
  already needed; the feature adds nothing to the manifest. No accessibility service, no device
  administrator, no `QUERY_ALL_PACKAGES`.
* **No exported surface.** The feature adds no activity, service, provider or receiver, and stores
  nothing in `Intent` extras.
* **No device state is altered.** Nothing is disabled, removed or hidden from Android, and the
  record itself is a Nivara preference that no other application can see.

## Verification status

The storage layer, the domain rules and the screen's state machine are covered by JVM tests that run
without a device: the file format's rejection of every damaged shape it could meet, the repository's
atomic replacement, idempotence, concurrent changes and failure mapping against real files, the
independence of the hidden and protected sets, the stale-row refusal, the session policy, and every
combination of section, query and read outcome. The instrumented screen suite is **compiled but not
executed**: no device or emulator is available in this environment, so nothing about how the screen
actually draws on Android, how the icon loader behaves, how TalkBack reads a row, or how navigation
reaches the destination is claimed as verified.

The repository checker (`tools/verify_nivara.py`) enforces the rules of this document statically:
the hidden domain stays free of Android types, the feature never touches component enabled state,
the package manager, accessibility or device administration; the UI never reaches storage; the
record has exactly one writer and one repository; the screen applies `SecureScreenEffect()`; the
destination is registered in the navigation graph; the record holds names and nothing else; and no
session-like or cryptographic type is declared in the feature. Each rule is negative-tested by
introducing the violation it forbids and confirming that the checker fails.
