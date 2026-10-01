# Sessions: the in-memory authorization state

This document is the design record for the session layer added in Stage 5. It explains what a
Nivara session is, when it exists, how it ends, and — just as importantly — what it deliberately
is not.

Scope: one authorization state, held in memory, that later features can ask before they show
anything protected. App Lock, the vault and hidden apps are *not* part of this stage; they will
consume this layer, not reimplement it.

## The model

| Type | Meaning |
| --- | --- |
| `SessionState` | `Unauthenticated`, or `Authenticated(source, startedAtMillis, expiresAtMillis)`. |
| `AuthenticationSource` | `Primary` or `Biometric` — how the session was opened, not what the credential was. |
| `SessionTimeoutPolicy` | A pure value: how long a session lasts, and when one that started at an instant ends. |
| `SessionManager` | The gate. `establish(outcome)`, `currentState()`, `isAuthenticated()`, `lockNow()`, and an observable `state`. |

`SessionState.Authenticated` carries three values and no others: the factor, the instant the
session started and the instant it ends. There is no credential, no derived key, no vault key, no
biometric key, no token and no "unlocked" flag anywhere in the type. A session is an authorization
state, not a secret — nothing about it would be worth stealing, and nothing about it can be
replayed to open anything by itself.

The gate is also deliberately ignorant of *what* the user entered. It never learns whether the
credential was a PIN, a password or a pattern; that belongs to the credential layer, where it is
already modelled.

```
primary credential success ─┐
                            ├─→ SessionManager ─→ SessionState.Authenticated
biometric success ──────────┘
```

## What opens a session, and what cannot

Exactly one thing opens a session: a **success**.

| Applied to the gate | Result |
| --- | --- |
| `AuthenticationOutcome.Succeeded` | Session opened, `source = Primary` |
| `AuthenticationOutcome.Failed` / `TemporarilyBlocked` / `NotConfigured` / `InvalidConfiguration` | No change |
| `BiometricAuthenticationOutcome.Succeeded` | Session opened, `source = Biometric` |
| Any other biometric outcome (failure, cancellation, Nivara's delay, Android's lockout, not enabled, invalidated, unavailable) | No change |

Two consequences follow, and both are tested:

- a failed authentication can never *accidentally* mean an open application;
- a failed authentication never *ends* a session that is already open either — mistyping while
  signed in is not a reason to sign the user out.

An expired session is not a failed authentication, and neither is a Quick Lock. Neither of them
touches the credential, the biometric key or any counter.

## The timeout rule

> A session expires a fixed interval after it is established. Nothing extends it.

The default is five minutes, as a value (`SessionTimeoutPolicy.Default`) rather than a number
compiled into screens, so a later stage can make it configurable without touching any UI.

- **Valid before, invalid at.** The interval is half-open: a session is valid strictly before its
  expiry instant and invalid at it. "Has this session expired?" therefore has exactly one answer at
  every instant — which is what makes the boundary testable rather than a matter of rounding.
- **Nothing refreshes it.** Not navigation, not activity, not a biometric success. Refresh-on-use
  is deliberately absent: extending a session should require the user's credential, not their
  attention. A second authentication establishes a *new* session with a new deadline.
- **Two ways the deadline is applied**, because either alone has a hole:
  - *on read* — every `currentState()` checks the deadline first, so no caller can act on an
    expired session even if the process was suspended, frozen or delayed;
  - *on a timer* — one job is scheduled for the deadline, so a screen that is merely watching sees
    the gate close by itself instead of showing protected content for a session that has ended.
- **The timer is treated as a hint.** Wall-clock time and elapsed time are different things, so
  when the timer wakes it re-reads the clock and ends the session only if the deadline has really
  passed. A device clock moved backwards leaves a session alone; a clock moved forwards cannot keep
  one alive past a read.
- **Time comes from `TimeProvider`**, the same abstraction the credential and biometric layers use,
  so the whole rule is testable without a device and without sleeping.

The honest trade-off: an attacker who can change the device clock can distort a session's
remaining time, and one who controls the unlocked device can do more than that anyway. Nothing in
the session is a boundary against someone who already has the device in their hands.

## Quick Lock

`sessionManager.lockNow()` is the one canonical lock. It:

1. discards the in-memory session, so nothing that authorized protected content survives the call;
2. cancels the pending expiry job, so the old session's timer cannot act later;
3. leaves the application unauthenticated, requiring a fresh authentication for the next protected
   action;
4. is idempotent — locking something already locked is a no-op, not an error.

It does **not** delete the credential, disable biometric unlock, remove the biometric key, write a
"locked" flag anywhere, or record a failure in either counter. Later features (App Lock, the vault)
call this same method instead of building a second lock.

## Memory-only, by design

The session lives in one field of one object. There is no `SharedPreferences`, no DataStore, no
file, no database and no encrypted session token — the design record says so, and the constructor
enforces it: `InMemorySessionManager` takes a clock, a policy and a coroutine scope, and has no way
to reach storage at all.

| Situation | Result |
| --- | --- |
| Process alive with a valid session | `Authenticated` |
| Session expired | `Unauthenticated` (on read, or on the timer) |
| `lockNow()` | `Unauthenticated`, immediately |
| Process killed | Nothing to recover: no session was ever written |
| Process recreated | `Unauthenticated` |

A JVM unit test constructs a second manager against the same clock and asserts it knows nothing
about the session the first one holds — which is exactly what a recreated process would find.

## Separation from the existing authentication systems

The session layer duplicates none of them, and shares no state with any of them.

| Concern | Owner | Relationship to the session |
| --- | --- | --- |
| Primary credential policy | Stage 3 | Authoritative for credential attempts; a success is *input* to the gate |
| Biometric policy and lockout | Stage 4 | Authoritative for biometric attempts; a success is *input* to the gate |
| Android's biometric lockout | Android | Never touched, never shortened, never claimed as clearable |
| Session timeout | Stage 5 | This document |
| Quick Lock | Stage 5 | Explicit invalidation; not a failure, not a lockout |

Neither throttling counter is advanced, reset or read by anything in the session layer — a
consequence of the manager's dependencies, verified by a test that drives the real trackers and the
real session manager together and compares the counters before and after: locking, expiring and
establishing all leave both of them byte-for-byte as they were.

Biometric failures never reach the credential counter, credential failures never reach the
biometric counter, and the session never reaches either.

## Where the code lives

```
domain/security/SessionState.kt          The session value and its boundary rule; Android-free
domain/security/AuthenticationSource.kt  How a session was opened
domain/security/SessionTimeoutPolicy.kt  The timeout rule, as a pure value
domain/security/SessionManager.kt        The gate contract
data/session/InMemorySessionManager.kt   The single implementation; memory-only
ui/session/SessionMessages.kt            Session text for the UI
ui/home/                                 Session status and Quick Lock on the home screen
ui/credential/                           Primary verification opens the session
ui/biometric/                            Biometric success opens the session
```

One manager per process, created lazily by `AppContainer` and shared through it: two session
objects would be two answers to one question.

## UI integration

The session is *observed* from one place and *read* from others — no screen ever writes to it.

- **Home** shows whether Nivara is unlocked and how it was opened, offers **Lock now** while there
  is something to lock, and stops showing an open session the moment the gate closes. It never
  counts down: the remaining time is the manager's business, and a screen that tracked it would be
  a second source of truth.
- **Primary verification** hands its finished outcome to the gate and renders what comes back. On
  success the screen shows the session; when the session ends — timeout or a lock from anywhere —
  the screen returns to asking for the credential with a short notice explaining why.
- **Biometric settings** hands a successful authentication to the gate too, so a biometric success
  is a session factor exactly like a credential success, and nothing more.
- Screens keep `SecureScreenEffect`, unchanged from Stage 3.

## Verification status

What is verified automatically, on the JVM, against the real implementation:

- session establishment from a primary success and a biometric success, and from nothing else;
- validity before, at and after the expiry boundary; the timer closing the gate on its own; a
  mis-set clock not ending a live session;
- Quick Lock's immediacy, its idempotency, and the requirement to authenticate again afterwards;
- session state carrying no credential or key material;
- the separation from both throttling counters, with the real trackers and the real file stores.

What is **not** verified:

- the real-device biometric flow that Stage 4 already documented as unverified. Nothing in this
  stage changes that: Android's prompt, the Keystore key's per-use authorization, enrolment
  invalidation and a full enable → authenticate → disable pass still require a device with an
  enrolled biometric, and the instrumented tests that touch them remain **compiled but not
  executed** in this environment.
- process death itself cannot be simulated in a JVM test; what is tested instead is the property
  that makes the outcome inevitable — the manager has no persistence dependency at all.
