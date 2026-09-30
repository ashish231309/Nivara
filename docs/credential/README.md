# Primary credential: enrolment and verification

This document describes the credential layer added in Stage 3. It is the design record for the
primary authentication credential — how it is chosen, entered, validated, derived from, verified,
changed, and what is written to disk.

Scope: one primary credential, of one of three methods — PIN, password or pattern. What the
credential will eventually *unlock* (a session, the vault, protected applications) is not part of
this layer and is not implemented here.

## The model

| Type | Meaning |
| --- | --- |
| `PrimaryCredentialType` | `Pin` (id 1), `Password` (id 2), `Pattern` (id 3). IDs are persisted, so they are permanent and never reused. |
| `CredentialStatus` | `NotConfigured`, or `Configured(type)` — exactly one method at a time. |
| `AuthenticationOutcome` | `Succeeded`, `Failed(blockedForMillis)`, `TemporarilyBlocked(retryAfterMillis)`, `NotConfigured`, `InvalidConfiguration`. This is the boundary a later session manager consumes. |

Exactly one credential is active at all times, and that is a property of the structure rather than
of the UI:

- storage holds a single record; there is no list to append to;
- `CredentialStore` deliberately has no `delete`, so no part of the application can remove the
  credential without going through the change flow;
- `enroll` refuses with `AlreadyConfigured` if a record already exists;
- `change` authenticates the current credential before it writes, and writes a replacement rather
  than an addition.

## What is persisted

Two small files in the application's private directory (`filesDir/security/`):
`credential.nvc` and `credential-attempts.nva`. Neither contains the credential, the derived key,
the recovery key or any other secret.

### Credential record (`NVCR`, version 1)

| offset | size | field |
| --- | --- | --- |
| 0 | 4 | magic `NVCR` |
| 4 | 1 | record format version (1) |
| 5 | 1 | credential type id |
| 6 | 1 | key derivation algorithm id |
| 7 | 1 | key derivation parameter version |
| 8 | 4 | iteration count, big-endian |
| 12 | 1 | salt length in bytes |
| 13 | n | salt |
| 13+n | 32 | verifier |
| 45+n | 4 | CRC-32 of everything before it, big-endian |

Decoding is strict: an unknown magic, version, type or algorithm, a parameter set below the policy
minimums, a length that disagrees with the declared salt length, or a checksum mismatch all produce
"no usable configuration" (`CredentialFailure.InvalidConfiguration`). Nothing is repaired and
nothing is guessed.

The salt and the parameters are stored because they are needed to re-derive a key from a submitted
credential, and because a later build must be able to read a record written with an older (lower)
work factor. They are not secret: salt and iteration count are exactly the values an attacker who
steals the file would need anyway.

### Attempt counters (`NVAT`, version 1)

Twenty-one bytes: magic, version, consecutive failure count (4 bytes big-endian), block end in
milliseconds since the epoch (8 bytes big-endian), CRC-32. Two numbers and nothing else — no
per-attempt history, no timestamps of individual attempts, nothing that describes the user's
behaviour beyond what the throttling rule needs.

### Why the record is not encrypted

The record carries nothing confidential: the verifier is one-way, the salt and parameters are
public by design, and the type is visible on screen anyway. Encrypting it would add a dependency on
the platform key store for the *authentication* path — if that key became unavailable (a reset, a
lock-screen change, a device migration), the credential could no longer be verified at all — in
exchange for protecting data that is not secret. Confidentiality of the credential itself comes
from the key-derivation work factor, not from the file.

What the record does need is integrity against *accidental* damage and *silent* truncation, which
is what the atomic write and the checksum provide. The CRC is explicitly not a MAC: anyone who can
write to the application's private directory can rewrite the file and its checksum. Such an
attacker cannot derive a key from it, and the throttling counters are not treated as a security
boundary for exactly this reason.

## Derivation and verification

The layer adds no cryptography of its own. It uses the Stage 2 `KeyDerivationService`
(PBKDF2-HMAC-SHA-256) with its salt generation and its parameter validation.

**Verifier construction.** The record stores `HMAC-SHA-256(derivedKey, label ‖ 0x00 ‖ typeName)`
with the versioned label `nivara.credential.verifier.v1`.

Why not store the derived key: the key that PBKDF2 produces from the credential is the key that
will protect the vault, so writing it to disk would turn a file that *can be used to check a guess*
into a file that *is the key*. The HMAC is one-way, and brute-forcing a candidate credential still
costs one full PBKDF2 derivation per guess, because the HMAC itself is cheap.

Why the type is part of the label: a record cannot be relabelled from one method to another and
still verify, and domain separation keeps this use of the derived key distinct from every other use
in the cryptographic layer.

**Verification flow:**

1. If a delay is still running, the attempt is refused without being evaluated
   (`TemporarilyBlocked`).
2. Load and validate the record. An unreadable record is a statement about Nivara's own data, not
   about the credential (`InvalidConfiguration`), and it does not count as a failed attempt.
3. Derive a key from the submitted credential with the *stored* salt and parameters.
4. Recompute the verifier and compare it with the stored one using a constant-time comparison
   (`MessageDigest.isEqual`).
5. On a match, reset the attempt counters and report `Succeeded`. On a mismatch, count the failure
   and report `Failed`.

Wrong credential, credential of the wrong type, and a record whose type byte was edited all produce
the same outcome. Nothing about which step failed reaches the caller, the UI or a log.

**Handling of inputs.** Every `CredentialManager` entry point takes ownership of the buffers it is
given and clears them before returning — on success, on failure and on cancellation. PIN digits and
password characters are `CharArray`s, never `String`s, and the derived key is cleared by the layer
immediately after the verifier is computed.

## Pattern canonicalisation

A drawn pattern is a raw touch sequence, and the same drawing can produce different sequences (a
finger may report a dot twice; a line from corner to corner passes over a dot it may or may not
touch). Canonicalisation before derivation is what makes one drawing verify consistently.

Grid, indexed row by row:

```
 0 1 2
 3 4 5
 6 7 8
```

Rules, applied in order:

1. A point outside the grid makes the sequence invalid.
2. A point equal to the previous point is ignored (a repeated touch, not a second visit).
3. A point already connected earlier makes the sequence invalid (each point is visited once).
4. If the move crosses the centre of an *unvisited* grid point, that point is inserted first,
   because the line visibly passes through it.

The canonical form is one character per connected point, `'0'`–`'8'`, in visit order. Examples:

| Raw touch sequence | Canonical form | Why |
| --- | --- | --- |
| `0, 2` | `012` | the line crosses `1` |
| `0, 8` | `048` | the diagonal crosses `4` |
| `0, 7` | `07` | a knight move crosses no centre |
| `0, 0, 1` | `01` | the repeat is ignored |
| `0, 4, 0` | invalid | `0` is connected twice |

The canonical characters are produced directly into a `CharArray`, which is cleared after use. The
sequence is never stored, never displayed after submission and never logged.

## Policy

| Method | Rules |
| --- | --- |
| PIN | 4–12 digits; digits only; not a single repeated digit; not a straight run (`1234`, `9876`); not on a short list of the most commonly chosen PINs. |
| Password | 8–128 characters; not blank; no ISO control characters; not on a short list of famously weak passwords. |
| Pattern | at least 4 connected points; no repeated point; not a full sweep of the grid. |

No character-class, symbol, rotation or expiry rules. Length is the only requirement that reliably
buys entropy, and forced complexity pushes people towards weaker secrets and towards writing them
down. Every check is deterministic and local: nothing is downloaded, nothing is measured against a
network service, nothing is transmitted anywhere.

## Attempt throttling

The schedule is `ExponentialThrottlePolicy`: the first two rejections are free, then the delay
starts at five seconds and doubles per rejection, capped at five minutes.

- **It is a delay, not a lock.** The counter resets on a successful authentication and only then;
  a delay expiring does not forgive the failures that caused it. Because the delay is capped, this
  cannot grow into a permanent lock, which the design forbids: a permanent lock is a recovery
  concern, not a rate limiter.
- **It is not a security boundary.** An attacker who can write to Nivara's private directory can
  clear the counter. The cost that actually protects the credential is the derivation work factor;
  throttling raises the price of guessing at the UI.
- **It uses a wall clock**, injected as `TimeProvider`, so it survives a restart. A user who moves
  the device clock can shorten or extend a delay; that is acceptable for a rate limiter and is
  documented rather than hidden.
- **Storage trouble is never fatal.** A counter file that cannot be read is treated as "nothing
  recorded", and a failed write does not fail the authentication being performed. A damaged counter
  must never become a lock.

## Change, and the vault-key integration point

`change(current, credential, confirmation)` re-verifies `current` through the ordinary
verification path — so the throttling rules apply to credential changes too — and then writes the
new record. All three directions (PIN → password → pattern → PIN) are supported, and the previous
credential stops working the moment the record is replaced.

No data is currently protected by the credential-derived key, so a change rewrites one record and
nothing else. When the vault exists, the credential-derived key will wrap (or be the input to
wrapping) a random content key. At that point a credential change will have to re-wrap under the
new derived key while the old key is still in memory — the integration point is
`ContentKeyWrapper` in the cryptographic layer, and the vault stage owns the decision between
re-wrapping the content key during a change and keeping the content key protected by a
device-bound key with the credential used only for authentication. Either way the contract is the
same: no plaintext key material is written at any point.

## What is deliberately absent

- **No reset and no removal path.** `CredentialStore` has no `delete`. Removing the credential
  without authenticating would be a bypass, and an "easy reset" is exactly the convenience that
  becomes a back door. Recovery is a separate mechanism with its own threat model and is built in a
  later stage.
- **No master password, no recovery code, no developer bypass.** There is no value anywhere in the
  code that can produce an accepted credential without knowing it.
- **No session, no lock, no timeout.** `Succeeded` is where this layer stops; what an accepted
  credential unlocks — and for how long — belongs to the session stage.

## Platform limitations, stated plainly

- **The Android Keystore is not exercised here.** This layer uses no platform key store; it is
  built on PBKDF2 through the platform provider, which behaves identically on API 28 and on current
  Android.
- **`FLAG_SECURE` is best effort.** The credential screens set it, which keeps them out of the
  platform screenshot and screen-record paths and blanks the recents thumbnail on most devices.
  Some manufacturers ignore parts of it; a rooted device can bypass it; it does not stop a camera
  pointed at the screen, an accessibility service that reads the view hierarchy, or a third-party
  keyboard. The PIN is therefore entered on an in-app keypad, with no keyboard involved.
- **A Compose text field holds a `String`.** The password field's value cannot be zeroised; the
  field is cleared the instant the value is handed over, no `rememberSaveable` is used, and the
  limitation is documented rather than hidden.
- **A managed runtime cannot guarantee erasure.** Buffers are cleared as soon as they are no longer
  needed, which keeps lifetimes short; it is not a guarantee about the heap.
- **The clock can be moved by the user**, which affects delay windows only.

## Testing

The credential tests run on the JVM, with real cryptography: the platform's PBKDF2-HMAC-SHA-256,
real random salts, the real record format and the real file stores. Two things are substituted, and
neither is cryptography — the iteration count is reduced to the lowest the policy permits (a couple
of tests use the production count to prove the default is stored), and the clock is a value the
test controls. The key derivation is never mocked.

Covered: method selection and the single-active-method rule; enrolment, rejection and confirmation
mismatch for all three methods; correct and incorrect verification; change in all three directions
invalidating the previous credential; persistence across a fresh store; on-disk scans proving no
credential or derived key is written; attempt counting, delay and reset-on-success; and buffer
clearing on both success and failure.

**Execution status:** these are JVM unit tests. Instrumented tests are compiled but have never been
executed here, because no emulator is available.
