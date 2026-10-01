# Vault storage

This document records what Nivara's vault storage is, where it lives, what is written into the folder
the user chooses, which key protects it, how a vault is created and reopened, and what every failure
state means. It is the durable reference for the storage foundation, not a walkthrough of the screen.

It covers one stage:

* **[Stage 13: external encrypted vault storage](#stage-13-external-encrypted-vault-storage)** — the
  vault root, its structure, its authenticated metadata record, and creating a vault at a root the
  user selected.

What the vault stores is *not* covered here. Importing files, encrypting content, the vault index,
media, albums, search, trash and recovery are later stages, and this document says what they will
build on rather than anticipating them.

# Stage 13: external encrypted vault storage

## Purpose

A vault is only useful if it lives somewhere the user can see, keep, copy and move — an SD card, a
synced folder, a folder they back up — and if what sits there is useless to anyone who finds it. This
stage delivers the smallest thing that makes that true and honest:

* **one root**, chosen by the user, remembered durably, and never replaced or guessed at;
* **one structure** inside it: an authenticated record of the vault and a place for encrypted content;
* **one key hierarchy** built on the cryptographic layer that already exists;
* **one answer** to "what is at this root?" that distinguishes a folder with no vault from a vault
  that exists and cannot be opened, from a root that cannot be reached at all.

Nothing in this stage stores a file's contents, reads a media item, or lists what the vault holds.
The screen exists to choose a folder, create a vault in it, and report what is there.

## Supported mechanism: the Storage Access Framework

The vault root is a **document tree the user selects through Android's own folder picker**. Nivara
takes a *persisted* URI permission on that selection and stores the reference; from then on it may
read and write that one folder and nothing else.

The alternatives were rejected deliberately:

| Alternative | Why not |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | A permission for file managers; it grants access to all shared storage, which is far more than a vault needs |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Broad shared-storage access, and effectively unusable for writing from Android 10 onward; they would also put the vault outside the platform's document model |
| A path under `/storage/emulated/0/...` | Requires a storage permission, breaks on scoped storage, and would mean Nivara picking a location rather than the user |
| The application's private storage | Not the user's, not movable, not backup-able by them, and it would make the "vault" a second copy of what the application already holds |

The Storage Access Framework needs **no permission at all**, which is why the manifest carries none
for the vault: the user's act of choosing a folder *is* the grant, and it is scoped to that folder.

Consequences the implementation works with, rather than pretends away:

* a document cannot be replaced atomically, so the record is written through **two slots** (below);
* a provider may refuse a write, rename a document, or drop it silently, so every write is **read
  back and validated** before anything is reported as committed;
* a grant can be revoked, and storage can be removed, so both are reported as their own states
  rather than as a missing vault.

## Choosing the root

* Nivara **never selects a root by itself**. Not Downloads, not DCIM, not Pictures, not Documents, not
  a folder named after the application, and not its own private directory.
* The selection is made in Android's picker and handed back as an opaque reference. The domain layer
  never sees a `Uri`, and the screens never display one.
* Adopting a selection **takes durable access first** and stores the reference second. A selection
  the platform refuses to grant durable access for is refused outright: the previous selection then
  survives a failed attempt, which is what keeps a mistyped pick from forgetting where a real vault is.
* A newly adopted root is read immediately, so the screen always shows what is at the folder that is
  actually configured.
* Changing the root replaces the stored reference. Nothing is copied, merged or migrated between two
  roots, and a vault is never discovered at one root and used at another.

## Structure

Exactly two directories are created inside the root the user selected, and nothing else:

```
<the folder the user chose>/
├── nivara.meta/
│   ├── vault.0.nvm        the committed metadata record
│   └── vault.1.nvm        the other slot (present only while a write is being replaced)
└── nivara.content/        reserved for encrypted content — empty in this stage
```

A trash area, an album area, a thumbnail area, an index area and a key area are **not** created here.
They arrive with the stages that use them; creating them now would be inventing structure nothing
reads.

### Two slots and a generation

Because a document cannot be replaced atomically, a write goes to the slot that is **not** the current
record:

```
vault.0.nvm (generation 7)   vault.1.nvm (generation 6)   →  slot 0 is authoritative
write slot 1 with generation 8                            →  slot 1 becomes authoritative
delete slot 0, after the new record has been read back    →  slot 1 alone remains
```

A reader takes the highest generation it can authenticate. An interrupted write can therefore only
damage the slot that was not authoritative, and a leftover record never hides a newer one. Deleting
the old record is **cleanup, not the commit**: if the deletion fails, the commit still stands.

## Metadata format (version 1)

```
record — nivara.meta/vault.N.nvm
offset  0        4        5         6              14
        | "NVVM" | version| reserved | generation (8) | envelope |

envelope — the existing AES-256-GCM envelope, bound to EncryptionContext.VaultMetadata
payload — the plaintext of that envelope
offset  0        4        5         6          7               15                 31        33
        | "NVVP" | version| reserved | keyScheme| generation (8)  | vault id (16)    | length(2) | wrapped key |
```

* **The clear header is not secret.** It carries the marker, the format version and the generation, so
  Nivara can tell "there is no vault here" from "there is a vault here that I cannot open" *without*
  being able to decrypt anything. This is why a lost key is a different state from an empty folder.
* **The payload is sealed and authenticated** by `EncryptionService` under
  `EncryptionContext.VaultMetadata`. A record whose bytes were altered anywhere fails to open; there is
  no partial read and no fallback.
* **The generation is checked twice** — in the clear header and inside the sealed payload. A record
  whose two generations disagree is treated as damaged, so editing the header to promote an older
  record makes that record invalid instead of authoritative.
* **Nothing secret is stored in the clear**: the vault key exists only as a wrapped blob inside the
  sealed payload. There is no credential, no pattern, no verifier, no biometric material, no recovery
  secret and no raw key anywhere in the root.

The **version** is checked on the way in. A record written by a newer format is reported as
`UnsupportedVersion` with the version that was read, and is **never** replaced — a later version can
still open it. Unknown versions are never reinterpreted as known ones.

The stored location record holds one thing and nothing else:

```
vault-location.nvl — the application's private storage
offset  0        4        5         6            10
        | "NVLP" | version| reserved | length (4) | utf-8 reference |
```

It is the platform's own reference for the folder the user chose. It is not key material and not a
secret — the folder is usually visible in the user's own file manager, and what protects the vault is
the encryption, not where it sits — but Nivara never displays it and never writes it to a log. It is
written through the project's atomic-write discipline (temporary file, flush and `fsync`, rename).

## Key hierarchy

The vault key is generated **inside Nivara** and wrapped for storage; nothing is derived from a
credential and nothing is stored in the clear.

```
device key  (Android Keystore, alias nivara.vault.v1, non-exportable)
     └── wraps ─→  vault key  (256 random bits, one per vault)
                        └── sealed into the metadata payload
                                └── authenticates the record, and later encrypts content
```

| Concern | What holds it | Where it lives |
| --- | --- | --- |
| User authentication | the primary credential (PIN, password, pattern) | `docs/credential/README.md` |
| Session authorization | one in-memory session gate | `docs/session/README.md` |
| Vault key availability | the device key that wraps the vault key | Android Keystore, this stage |
| Content storage | the vault key, once a credential-derived and a recovery wrapping of it exist | later stages |

The four are separate on purpose: the session gate says whether a *user* may act; the device key is
what makes the vault's key material *available to the application*; and neither is the vault key
itself. Re-wrapping a content key never touches encrypted data, which is why this shape survives a
credential change and (in a later stage) a credential-based and recovery-based wrapping of the same
key.

What this stage deliberately does **not** do:

* no vault key is derived from the credential, and the credential never reaches the storage layer;
* no raw vault key is exposed globally: the repository unwraps nothing for inspection, and only this
  stage's own key-material check ever holds the in-process copy — which is cleared in a `finally`;
* no session is created, extended, ended or counted by anything in `data/vault`.

The cryptography is the existing one. `EncryptionService`, `ContentKeyWrapper`, `DeviceKeyStore`,
`SecureRandomGenerator`, `EncryptionContext` and the envelope format are used exactly as the security
layer defines them; not a cipher, nonce, KDF or key-store call is written in the vault code, and the
verifier refuses a vault source that reaches for one.

## Initialization

```
1. the stored root is read        → no root  ⇒ refuse (InvalidLocation)
2. the root is inspected          → a vault is there ⇒ refuse (VaultAlreadyExists /
                                    UnsupportedVersion), and unreadable records are refused unless
                                    the user has explicitly asked to replace them
3. two directories are created    → a refusal here is a failed write, nothing is claimed
4. key material                   → the device key is created, a vault key is generated, wrapped,
                                    and unwrapped again to prove the wrapping works
5. the payload is sealed          → authenticated under EncryptionContext.VaultMetadata
6. the record is written          → into the slot that is not the current record
7. the record is read back        → and opened again; only then is the write a commit
8. successes are reported         → the state is re-read from storage
```

The order matters at every step, and so does what is *not* done:

* **no premature success**: a vault exists only once the bytes on the user's storage have been read
  back, opened and found to carry the same identity and key material;
* **no partially valid vault**: a failure before the commit leaves the folder reporting an unfinished
  structure, which a retry completes; a failure during the commit leaves the previous record intact;
* **no silent overwrite**: a valid vault, and a vault from a newer format, are refused — replacement
  exists only for records that cannot be opened at all, only as an explicit action, and only with copy
  that says what it costs;
* **no substitution**: the root is never changed because the chosen one is unusable, and initialization
  never falls back to another directory.

## Reopening and reconnection

Opening a vault means the same read as inspecting one: the slots are listed, the highest generation
that authenticates is taken, and the state becomes `Ready` with the vault's identity and format
version. Nothing is written, repaired or created while reading, which is why simply showing the screen
is safe.

Reconnection is the ordinary route: choose the folder again. Because the vault's identity lives in its
metadata rather than its location, a vault that was moved, restored or copied to another folder is
still the same vault once its folder is selected again. The reference is the only thing kept for it.

## Failure states

No state in this stage means "empty vault". Each one means one specific thing, and each is shown with
its own words:

| State | Means | What the user can do |
| --- | --- | --- |
| `NotConfigured` | no folder has been chosen | choose one |
| `LocationUnknown` | a folder was chosen and the record of *which* one cannot be read | choose the folder again (nothing is deleted) |
| `Missing` | the folder is reachable and holds no Nivara vault | create a vault here |
| `Ready` | a valid vault: authenticated metadata and the structure it requires | — |
| `Unreadable` → `MetadataDamaged` | Nivara's record is there and cannot be opened: damaged, or written under a device key that is no longer the one in use | check the folder; replacing is the explicit, destructive option |
| `Unreadable` → `StructureIncomplete` | structure without a complete record: an initialization that did not finish | create the vault here, which completes the setup |
| `Unreadable` → `KeyUnavailable` | the vault is intact and the platform key protecting its key material is gone (removing the screen lock destroys it) | a later stage can re-wrap the vault key; replacing is the explicit option |
| `UnsupportedVersion` | a record written by a newer Nivara | update Nivara; nothing here may replace it |
| `Unavailable` | the storage cannot be reached right now | reconnect the storage and try again |
| `AccessDenied` | the persisted grant was revoked or the selection cannot be resolved | choose the folder again to restore the grant |

Two properties hold across all of them: a state is **never an authorization** (a `Ready` vault is
still behind the session gate for anything that would change it), and a damaged record is **never
deleted, repaired or replaced** by the act of looking at it.

## Atomicity

* the location record and every private-storage record use the project's atomic writer: temporary
  file, flush, `fsync`, rename;
* a record on the user's storage goes to the slot that is not authoritative, and the generation inside
  the record — checked in the clear header *and* inside the sealed payload — decides which slot wins;
* every metadata write is validated by reading it back and opening it before it is accepted;
* an interrupted write cannot damage the authoritative record, and a write that did not land is
  reported as a failure rather than as a vault;
* nothing is truncated in place, nothing is caught and returned as empty, and no failure is turned
  into a success.

These paths are tested on the JVM against an injectable storage double that can refuse a write, fail a
read, accept a write and store nothing, store something else, or refuse a deletion.

## Permissions

The vault adds **no permission to the manifest**. The declared permissions remain the two App Lock
requires (`PACKAGE_USAGE_STATS` and `SYSTEM_ALERT_WINDOW`, both granted by the user in Android's
settings); there is no `MANAGE_EXTERNAL_STORAGE`, no `READ_EXTERNAL_STORAGE`, no
`WRITE_EXTERNAL_STORAGE`, no `QUERY_ALL_PACKAGES`, no accessibility service, no device administration,
no notification and no foreground service for the vault. The Storage Access Framework is what makes
that possible: the picker grants durable access to exactly one folder.

## Security and privacy boundaries

* the vault folder is **not a secret**: it is a folder the user can open like any other, and the
  protection is the encryption, not its location;
* the vault's key material never appears in the clear — the vault key exists only wrapped, inside an
  authenticated envelope;
* the vault identifier is **not a secret and not a key**: 128 random bits, never displayed and never
  logged, used to recognise the same vault across folders and copies;
* no URI, path, file name, vault identifier or key is written to a log, a message or a screen;
* nothing is uploaded, transmitted, measured or reported: there is no network code in this stage and
  no analytics anywhere in the application;
* nothing about the vault is a hidden route: the screen is reached from the home screen like any other
  settings screen, under whatever identity Nivara presents, and it needs no session to *look* at;
* camouflage, the hidden set, App Lock and the launcher are untouched by this stage in both directions:
  the vault neither reads nor changes any of them, and none of them knows the vault exists.

## Scope

In scope, and delivered: the root contract, the platform storage seam, the versioned and authenticated
metadata record, initialization with read-back verification, the state model, the session-gated screen,
the home entry, the verifier rules and the tests.

Deliberately **not** in this stage, and not documented as if it were:

* importing files, encrypting content, chunked encryption and the vault index (Stage 14);
* media and document handling (Stage 15);
* albums, search and sorting (Stage 16);
* trash and restore (Stage 17);
* recovery and reinstallation beyond the durable-root contracts this stage establishes (Stage 18);
* the vault's visual polish (Stage 19).

## Runtime verification status

Everything in this document is verified by static checks and by local JVM suites, which run on every
change. The instrumented suite for the vault's screens is **compiled but not executed** in continuous
integration, because no device or emulator is attached: nothing here claims that the Storage Access
Framework behaves in a particular way on a particular Android version, that a provider persists a
write, or that a picker returns a grant — those are platform behaviours that require a device to
observe, and the code is written to fail safely when they do not hold.
