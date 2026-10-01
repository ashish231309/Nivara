# Vault storage

This document records what Nivara's vault storage is, where it lives, what is written into the folder
the user chooses, which key protects it, how a vault is created and reopened, and what every failure
state means. It is the durable reference for the storage foundation, not a walkthrough of the screen.

It covers three stages:

* **[Stage 13: external encrypted vault storage](#stage-13-external-encrypted-vault-storage)** — the
  vault root, its structure, its authenticated metadata record, and creating a vault at a root the
  user selected.
* **[Stage 14: file import, content encryption and the vault index](#stage-14-file-import-content-encryption-and-the-vault-index)** —
  choosing one document, encrypting it into the vault as a bounded-memory stream, and recording it in
  an authenticated index that is only ever replaced once its replacement has been read back.
* **[Stage 15: viewing a stored file](#stage-15-viewing-a-stored-file)** — opening one listed file
  again: what Nivara can draw, play and read, how the content is decrypted without a plaintext copy
  anywhere, and what happens when a file is unsupported, missing, unreadable, damaged, or opened while
  the session ends.

Still **not** covered here, because it does not exist yet: albums, search and sorting, tags and
favourites, trash and restore, permanent deletion, recovery after reinstalling, sharing or exporting —
and the vault's visual polish. This document describes what is implemented, and nothing else.

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

# Stage 14: file import, content encryption and the vault index

## Purpose

A vault is only worth having if a file can get into it, and it is only trustworthy if that first real
write cannot half-happen. This stage delivers the smallest honest version of that:

* **one document**, picked by the user through Android's own document picker, read once during the
  import and never modified, moved or remembered afterwards;
* **one encrypted object** per imported file, written as a bounded-memory stream under a temporary
  name and only then given its own;
* **one authenticated index** that names what the vault holds, replaced a whole generation at a time
  and only after the replacement has been read back and opened;
* **one screen section** that shows the list, offers the import, shows progress while it runs, and
  says exactly which of the many things went wrong when one did.

Video, audio, images and documents are all treated the same way here: a file is a name, a declared
type, a size, an arrival time and a stream of bytes. Understanding what is inside a file — rendering
it, extracting a thumbnail, reading its duration — belongs to the stage that presents it.

## The import pipeline, in order

```
 authorize → vault ready → index readable → allocate a random item id → open the source
     → validate the declared name and type → stream-encrypt into <id>.nvo.pending
     → flush and sync → rename to <id>.nvo → read back: digest the stored bytes,
       decrypt every record to the expected plaintext size → authorize again
     → seal a new index generation → write it into the slot that is not authoritative
     → read it back, authenticate it, compare its items → prune the superseded slot
     → only now is the item reported
```

Every step before the last can fail without leaving an item: an import reports success only after the
encrypted object has been written, read back, opened, and named by an index that itself was read back
and opened. A failure anywhere reports what did *not* happen, and never reports that the file is in
the vault.

## Content model

`VaultItem` is deliberately small, and every field has a reason to exist:

| Field | Why it is there |
| --- | --- |
| `id` — 128 random bits, lower-case hex | Names the encrypted object, binds every record of it, and is what a duplicate check compares |
| `name` — the file's own name | So its owner recognises it. Validated, bounded, never a path, never an object name |
| `mimeType` — the provider's declared type, if usable | A generic kind for the list; a claim, so it is validated and dropped when unusable |
| `sizeBytes` — the plaintext size | Read from the object that was verified, not from what the provider claimed |
| `importedAtEpochMillis` | The list's ordering and nothing else |
| `contentFormatVersion` | Which content format the object is in, so a later stage can migrate deliberately |
| `contentDigest` — SHA-256 of the stored ciphertext | Describes the bytes that are on storage; computed from the read-back, never from the write |

Not in the model, on purpose: albums, tags, favourites, ranking, thumbnails, duration, dimensions,
media metadata, trash, restore and any notion of a file being "moved in". Importing never deletes or
modifies the source; the vault keeps its own encrypted copy, and a file whose original is later moved,
renamed or deleted stays readable because nothing about the item refers to where it came from. The
source reference is used for one import and is **not stored in the index**.

## The encrypted object format (version 1)

A user file can be gigabytes, so it is not one envelope: it is a stream of authenticated records in
`data/security/EncryptedStream.kt`, driven by `EncryptionService.encryptStream` / `decryptStream`.

```
 header (41 bytes)
 offset 0        4        5         6        7          8           9            13        25
       | "NVCO" | version| reserved | context| keyScheme| algorithm | chunkSize(4)| nonce(12)| identity(16) |

 record, repeated to the end of the stream
 offset 0                  4             12           13            17            17 + length + tag
       | plaintextLength(4) | sequence(8) | flags(1) | ciphertextLength(4) | ciphertext + tag |

 the record's nonce is derived, not stored:  recordNonce(i) = headerNonce XOR bigEndian64(i + 1)
 the fourth byte of a record header is its last, non-final record: FLAG_FINAL = 0x01
```

* **Purpose.** Every stream is bound to `EncryptionContext.VaultContent` (tag `0x01`), written into
  the header and checked before a byte of ciphertext is read. The vault's own record uses
  `VaultMetadata` (`0x02`), and the index uses `VaultIndex` (`0x07`), so no ciphertext written for one
  purpose can be accepted as another.
* **Identity.** The item's 16-byte identifier is written into the header and bound — with the whole
  header — as associated data of every record. A stream written for one item cannot be read as
  another's, even under the right key.
* **Sequence and completeness.** Each record authenticates its own plaintext length, its sequence
  number, its flags and its ciphertext length. A reader requires the sequence to advance by one from
  zero, requires a final record, and requires nothing to follow it: a dropped record, two records
  swapped, a record duplicated, an edited length, or a truncated stream all fail rather than yield a
  shorter, reordered or different file.
* **Nonces.** One fresh random 96-bit nonce per stream, with the record counter exclusive-ored in, so
  no record in a stream reuses another's nonce and the header's own value is never used as a record
  nonce. Nothing derives a nonce from a name, an identifier, a time or a counter alone, and no nonce
  is ever written to output.
* **Memory.** Two fixed 64 KiB buffers are reused for a whole file: memory does not depend on the size
  of the file, and no code path holds a whole file, reads one with `readBytes()`, or base64-encodes
  one.
* **Where the cryptography lives.** `EncryptedStream` and the streaming methods on
  `JcaEncryptionService` are the only implementation; the vault calls them through `EncryptionService`
  and never reaches for a cipher, a digest or a random source of its own.

## Object names, temporary names and orphans

An object is named from the item's identifier and from nothing else: `<id>.nvo` for a completed
object, `<id>.nvo.pending` while it is being written. The original file's name never appears in the
content area, which is what makes a name provided by a document provider — a path, a traversal
sequence, a control character — impossible to turn into a location. The classifier recognises three
things and no more: a completed object (which may be an item), a pending object (which never is), and
anything else in the folder, which Nivara did not put there and will not touch.

That gives crash consistency a simple, checkable shape:

| Interruption | What is on storage | What the vault reports |
| --- | --- | --- |
| Before the object is created | Nothing | Nothing; the import failed |
| During encryption, or a refused write | Nothing: the pending document is removed | Nothing; the import failed |
| After the object is complete, before the index is committed | `<id>.nvo`, named by no index | An **orphan**: counted as unindexed, never shown as a file, and never deleted |
| After the index is committed | `<id>.nvo` plus an index naming it | The item |

Nothing but the index can make a file appear, and an object the index does not name is left where it
is: deleting content Nivara cannot match to a list is how a vault loses files. Only one deletion
exists in the whole pipeline — the object *this* import wrote and then could not commit, because the
session closed before the change was authorized.

## The index format (version 1)

`index.0.nvi` / `index.1.nvi` live in the metadata area next to the vault's own record, sealed under
`EncryptionContext.VaultIndex`.

```
 record  | "NVIN" | version | reserved | generation(8) | envelope |
 payload | "NVIN" | version | reserved | generation(8) | vault generation(8) | item count(2) | items… |
 item    | id(16) | name length(2) | name | mime length(2) | mime | size(8) | imported at(8) |
         | content format(1) | content digest(32) |
```

* **The clear header is not secret and not encrypted.** It says this is a Nivara index, which version
  wrote it and which generation it is, so a reader can tell "no index yet" from "an index I cannot
  open" and from "an index a newer Nivara wrote" without holding a key.
* **The payload is sealed by the existing cryptographic layer** under a purpose of its own, so an
  index envelope can never be accepted where the vault's record is expected, or the other way round.
  The index does not store a key, a wrapped key, a nonce or a source reference.
* **The generation is checked twice** — in the clear header and inside the sealed payload. Editing the
  header to promote an older index therefore makes that index invalid rather than authoritative.
* **Parsing is bounded and strict.** Every length is checked against a bound before use, every string
  is validated as a name or a type, identifiers must be exactly sixteen bytes of hex, a duplicate
  identifier invalidates the whole record, and trailing bytes are refused. A failure to parse is never
  an empty list: an index with no items is a valid record with a count of zero, and anything else is
  reported as damage.
* **Replacement is a two-slot commit.** A new generation is written into the slot that is *not*
  authoritative, read back, opened and compared item by item; only then is the superseded slot pruned.
  If anything fails, the previous index is left exactly as it was — it is never truncated first.

## Which key, and how it is used

Stage 13's hierarchy is unchanged and is the only one: the device key in the platform key store
(alias `nivara.vault.v1`) wraps the vault key, which is sealed inside the vault's authenticated
metadata record. The content path borrows that same vault key for the duration of a call, through the
smallest contract that does so — `VaultKeyAccess` in the data layer — and the key is cleared when the
call returns, whatever happened.

There is no separate content password, no credential-derived content key, no per-item key stored
beside the object, and no second encryption service. The item's identity is the binding: it is
authenticated into every record, so an object cannot be read as another item's. Key material never
reaches a screen, a saved state, the index, preferences or any output.

## Failure states

| What happened | What the user sees |
| --- | --- |
| No vault, or a vault that cannot be opened | The vault's own state, unchanged from Stage 13; the list is not shown as empty |
| The index exists and cannot be read | "File list cannot be read" — and importing is refused, because appending to a list Nivara cannot read could replace it |
| The index was written by a newer Nivara | "File list from a newer Nivara"; nothing is written over |
| The index is full | The import is refused; the format's bound is reported rather than worked around |
| The selected document cannot be read, or access was withdrawn | The import fails, and the file is not in the vault |
| The document is larger than the import bound (16 GiB) | Refused from the declared size and again while reading |
| The vault's storage is unreachable or refuses a write | The import fails; nothing is indexed and the previous index is untouched |
| What was written did not come back as it was written | The import fails rather than claiming the file is in the vault |
| The platform key protecting the vault is gone | Reported as the vault's own key failure |
| The session closed before the import was authorized | The import is abandoned, its unfinished object removed, and the user is sent to the existing unlock screen |

## Concurrency, cancellation and the session

* **One import at a time.** A single lock covers the whole pipeline, so two imports cannot interleave
  their index generations, and each import has its own identifier, its own temporary object and its
  own encryption state. Two imports that run at once both end up in the index; neither can overwrite
  the other.
* **Reads are not blocked.** Reading the index takes no lock: a commit in progress is invisible until
  it finishes.
* **Cancellation is safe by construction.** If the import is cancelled — the screen goes away, the
  process is killed — the pending document is removed and no index record was written, so the vault
  is exactly as it was.
* **The session is asked, never extended.** The import asks the existing session manager whether the
  gate is open before it starts and again before the index is committed. A long encryption does not
  extend the session and there is no separate import session. A session that closes mid-import stops
  the import; the file the user picked is **not** remembered for a later unlock, because starting a
  write after some other unlock would be a change they did not ask for at that moment.

## Permissions and platform boundaries

The document picker uses the Storage Access Framework's single-document chooser and grants access to
that one document. Nivara does **not** persist that grant: the document is read during the import and
never again, and no source reference is stored. The vault root keeps the separate *persisted* grant it
already had, and the two are never confused — a source reference is not a vault location, and an
object's name is not a path. No storage permission is involved (no `MANAGE_EXTERNAL_STORAGE`, no
`READ_MEDIA_*`), Nivara still never creates a folder of its own choosing, and the platform document
API remains behind the data layer: the vault's domain and pipeline never see a `Uri`, a
`ContentResolver` or a `DocumentFile`.

## Security and privacy boundaries

* No filename, URI, path, plaintext, key or nonce reaches any output; failures are fixed, non-secret
  sentences.
* No network, no analytics, no telemetry and no usage history: importing is entirely local.
* No plaintext temporary file exists at any point — the only temporary object is the ciphertext under
  a pending name, inside the vault.
* The original file is never deleted, modified or moved.

## Scope

Delivered: the content model, the streaming content format and its service methods, the import
pipeline with injectable storage failures, the authenticated index and its two-slot commit, the
document picker and its adapter, the index state in the vault screen, the verifier rules and the
tests.

Deliberately **not** in this stage: rendering, opening or playing an imported file, thumbnails, media
metadata, albums, search, sorting, tagging, favourites, trash, restore, permanent deletion,
deduplicating identical content, rebuilding a damaged index, scanning the content area to reconstruct
a list, and recovery after reinstalling (Stages 15–18).

## Runtime verification status

The content model, the index format, the streaming format and the import pipeline are verified by
local JVM suites and by the static checks, which run on every change. The instrumented suite for the
vault's screens is **compiled but not executed** in continuous integration, because no device or
emulator is attached. Nothing here claims that a particular provider returns a display name or a size,
that a provider honours a rename, that a large file encrypts at a particular speed, or what a device
does when the process is killed mid-import — those are platform behaviours that require a device to
observe, and the code is written to fail safely when they do not hold.


# Stage 15: viewing a stored file

## Purpose

An imported file is only worth keeping if it can be opened again, and it is only worth trusting if
opening it does not undo what the vault is for. This stage delivers the smallest honest version of
that:

* **one classifier** that decides what a file is from the type the index authenticated, never from its
  name;
* **one content reader** that every viewer reads through — the streaming decryption Stage 14 already
  wrote, served in bounded pieces, with the session asked before each piece;
* **one viewer per kind** that this build can genuinely show: a picture, the platform's media stack for
  video and audio, bounded text, and PDF pages rendered through a proxy file descriptor;
* **one description** for everything else, with the file's facts and no guessing;
* **one ending**: when the session ends — by itself or through Quick Lock — the reader stops, the
  player and every decoder are released, and the screen says the vault is locked.

Nothing here creates a second vault key, a second root, a second index, a second authentication
prompt or a second encrypted format. A viewer is a *reader* of what Stage 13 and Stage 14 already
write.

## What a stored file is, and which viewer it gets

The classification lives in the domain, is free of Android types, and is the same one the list uses —
so the label on a row and the viewer that opens from it can never disagree. It works from the declared
type stored in the authenticated index:

* the type is normalised first (lower case, parameters such as `; charset=utf-8` removed);
* the **family** comes from the type itself: `image/`, `video/`, `audio/`, `text/`;
* a type that is missing, blank, over-long or malformed is treated as no type at all, and the file is
  `Other` — a generic file with its facts, never a guess from its extension;
* a document Nivara recognises but cannot render is still a **document**: the screen says plainly that
  this build has no viewer for it rather than pretending the file is something else.

Nothing about the classifier is stored. The index keeps what the provider declared and the bytes; the
classification is presentation, so a later stage can classify something differently without a single
file being rewritten.

## Formats: what is shown, played or read, and what is only described

The set is bounded on purpose. "Whatever the platform happens to accept" is not a policy — it would
make what Nivara shows depend on the device and the codec pack of the moment — so these are the sets
Nivara attempts, and everything outside them is a generic file with metadata.

| Kind | Types Nivara attempts to show | How |
| --- | --- | --- |
| Image | `image/jpeg`, `image/png`, `image/webp`, `image/gif`, `image/bmp`, `image/heic`, `image/heif` | The platform's image decoder, sampled to a bound |
| Video | `video/mp4`, `video/webm`, `video/3gpp`, `video/mpeg`, `video/x-matroska` | The platform's `MediaPlayer`, over a media data source fed by the vault's decryption |
| Audio | `audio/mpeg`, `audio/mp4`, `audio/aac`, `audio/wav`, `audio/x-wav`, `audio/ogg`, `audio/flac`, `audio/x-flac`, `audio/opus` | The same player, with no drawing surface |
| Text | `text/plain`, `text/csv`, `text/markdown`, `text/xml`, `application/json`, `application/xml` | Read into a bounded preview through the content handle |
| Document | `application/pdf` | `PdfRenderer` over a proxy file descriptor served from the decrypted stream |
| Described only | `application/msword`, `application/vnd.ms-excel`, `application/vnd.ms-powerpoint`, `application/rtf`, `text/rtf`, `application/zip`, `application/x-tar`, `application/gzip`, and every `application/vnd.openxmlformats-officedocument.*` | No viewer: the file's name, type, size and arrival time, and a sentence saying this build cannot show it |
| Everything else | Anything whose declared type is missing, malformed or unfamiliar | The same description, with no claim about what is inside |

Two honest qualifications belong here rather than in a later correction:

* **Attempting is not guaranteeing.** Whether a particular device decodes a particular HEIC photograph,
  a particular Matroska video or a particular Opus stream is the platform's answer, not Nivara's. A
  file the platform refuses becomes "this device could not display the file" — never an empty vault,
  never a claim of damage.
* **Office documents and archives are represented, not rendered.** Nivara recognises them as documents,
  lists them with their facts, and explains that there is no viewer. It does not extract their
  contents, does not hand them to another application, and never claims otherwise.

## Reading content: one decryption path, in bounded pieces

Everything a viewer reads comes through one content reader, which is the streaming decryption of
Stage 14 running as a producer into **one bounded pipe** (128 KiB):

```
storage ──ciphertext──▶ existing decryptStream ──▶ one bounded pipe ──read()──▶ decoder
                                                    producer coroutine
```

* The decryption is not re-implemented, wrapped or shortened: a viewer that needed its own cipher
  would be a second set of rules for the same format. The verifier refuses a cipher, a digest, a MAC,
  a random source, a key type, a key store, the encryption service or the vault's key borrow anywhere
  in the viewing layer.
* Nothing is buffered whole. An item is never read into a `ByteArray`, never converted to base64, and
  never written to the application's private storage to be rendered from. The only memory a read
  costs is the pipe, the service's own chunk buffers, and whatever a decoder allocates.
* **The handle is sequential, and stays sequential.** Every record of the object authenticates in
  order, so a reader that could ask for an arbitrary offset would be trusting bytes it had not
  checked. `restart()` begins the item again from its first record — that is what an image decoder
  scanning a file twice, or a player seeking backwards, does instead of keeping a copy. Seeking
  *forwards* decrypts and discards the bytes in between: bounded memory, not constant time, and the
  viewer's own documentation says so.
* **The session is asked before every piece**, and the answer decides whether the next piece is
  served. A session that ends mid-read stops the read at the next read, whatever the screen is doing,
  so no plaintext is produced after the gate has closed — even if a screen never noticed.
* A failed authentication is a failure, never a shorter file: a corrupt record ends the read with a
  typed failure rather than being reported as the end of the item. A part of a file is not the file.

## The picture

The image engine decodes within an explicit bound, because a bitmap is the one place where a viewer's
memory is inherently proportional to the content:

* the decoder is asked for the dimensions first, and a declared size beyond 32 768 pixels on a side is
  refused rather than sampled;
* sampling is then computed from those dimensions — a power of two, so the decoded image stays within
  2 048 pixels on its longest side **and** within 4 000 000 pixels in total;
* the item is restarted and decoded with that sampling, so a 50-megapixel photograph costs what a
  screen can show rather than what the file holds;
* the previous decode is recycled before a new one replaces it, and the picture is released when the
  viewer closes;
* the screen fits the picture to the width, offers a double-tap zoom to twice that, and says when a
  picture was reduced — a softer image should not be a mystery.

## Video and audio

Both are the same engine over the platform's `MediaPlayer`, and they differ only in whether there is a
drawing surface:

* the player never receives the vault's key. It receives a **media data source**: the platform's own
  abstraction for "give me the bytes at this offset and tell me how long you are", backed by the
  content handle. The size the source reports is the size the authenticated index recorded;
* playback controls are play, pause, a seek bar, the position and the duration where the platform
  reports one, and a stop that is the release. A file whose length the player cannot report shows a
  position with no total rather than inventing one;
* the video surface belongs to the composable that draws it: it is handed to the engine when it exists
  and withdrawn when it is destroyed, and the engine never holds it beyond that;
* nothing plays in the background. Leaving the screen pauses playback; leaving the viewer releases the
  player, the source, the content and the key borrow;
* there is no plaintext video anywhere: no cache file, no whole-file buffer, no decoded copy on disk.

## Documents

* **Plain text** is read through the content handle into a preview bounded at 256 KiB, in 64 KiB
  pieces. The whole object is still decrypted — that is what makes a damaged file fail instead of
  showing a prefix — while only the bounded part is kept, and the screen says that it is showing the
  beginning and how many characters that is.
* **PDF** is the one platform API that genuinely needs seekable access: `PdfRenderer` cannot read a
  stream. Nivara hands it a **proxy file descriptor** — the platform's mechanism for a descriptor
  whose contents the application produces on demand — over the same content handle. There is no
  plaintext file to leak: the descriptor is a kernel object that exists for the length of the viewer,
  is served from the decrypted stream in bounded pieces, and disappears when the session is released.
  This is the stage's whole answer to "a platform API requires seekable access": the safe mechanism
  the platform provides, rather than a temporary decrypted copy of the file.
* Pages are rendered one at a time into a bitmap bounded by the same maximum dimension the image
  viewer uses, and released as the reader moves on.
* **What that leaves unverified is stated plainly:** the render path needs a device with a PDF
  renderer, so the JVM suites verify the engine's seams and the failure vocabulary, and the
  instrumentation compiles the screen's half. Rendering a real PDF is verified only when the
  instrumented suite runs on a device with content that a person imported.

## Unsupported, missing, unreadable, damaged and locked

Five sentences, kept apart, because collapsing them would be the mistake the whole vault exists to
avoid:

| State | What it means | What the screen offers |
| --- | --- | --- |
| Unsupported | The file is in the vault, its content is intact, and this build has no viewer for its type | The file's facts and an explanation; no controls, no retry |
| Missing | The index names the file and its encrypted object is not in the vault | The entry, the explanation that nothing was deleted or forgotten, no retry |
| Unreadable | The vault's storage could not be reached or refused the read | The explanation, and try again |
| Damaged | The bytes did not authenticate; none of the file was shown | The explanation that not even the beginning is displayed, no retry |
| Could not be displayed | The bytes authenticated and this device's decoder refused them | The explanation that the file is unaffected, and try again |
| Locked | The session is not open | The existing unlock action, which leads to the credential screen that already exists |

Missing, unlisted and unfinished content is first class, exactly as Stage 14 made it: a missing object
never empties the vault, never removes the entry and never triggers a "repair", a viewer that cannot
render a file never mutates the index, and one bad item never takes the list down with it. A viewer
reads; it never imports, deletes, renames, creates, commits an index or deletes a temporary object.

## The session, and Quick Lock

The viewer has no session of its own. It asks the existing `SessionManager` whether the gate is open,
watches the same gate while it is open, and never calls `establish` or `lockNow`, never refreshes a
session because media is playing and never extends a timeout. Both halves are enforced: the verifier
refuses those calls in the viewing layer, and a JVM suite drives the viewer through a recording
wrapper around the production session manager and asserts that the viewer only ever *reads* it.

**Quick Lock** is therefore already covered by the design rather than special-cased: locking the
application from anywhere ends the session, the viewer's watcher releases the player, the decoders and
the content, the state becomes locked, and the reader independently refuses the next piece because the
plausible answer to "may I serve this byte?" is now no. Reopening goes through the existing
authentication path — the credential screen the whole application already uses — and then the same
item can be opened again. A fake-session test proves the viewer responds to invalidation.

## Lifecycle

The viewer is part of the vault screen rather than a destination of its own: it is one file from the
list, so leaving it leaves nothing behind, and the view model that owns its content is cleared with
the screen. There is no saved instance state for the viewer — a restorable viewer would be a viewer
that claims to still hold content it has released.

* **Entering** asks the gate; a closed gate shows the locked state and reads nothing.
* **Leaving** (the back key, the explicit close action, or the screen going away) releases the player,
  the media source, the document session, the renderer, the decoders, the pipe and the key borrow.
  `onCleared` releases them too, so no borrow outlives the viewer.
* **The screen leaving the foreground** pauses playback; coming back asks the gate again.
* **Repeated closes are safe**, controls with nothing open are ignored, and a second tap cannot open a
  second item over one that is still opening.

## The list, and thumbnails

The list is Stage 14's with one addition: each row shows the file's name, its type (as a word, from
the one classifier), its size and when it arrived, and a tap opens it. Nothing else was added — no
sorting controls, no search, no albums, no tags, no trash.

There are **no thumbnails**: no thumbnail is generated, stored, cached or decoded ahead of time, and
the list draws an icon for the file's *kind* rather than a picture of its contents. That is a
deliberate limit, not an omission: a thumbnail is decrypted content, and a stored one would be a
plaintext picture of the vault's contents sitting outside the encryption the vault exists to provide.

## Permissions and platform boundaries

No permission is added by this stage — no `READ_MEDIA_*`, no `MANAGE_EXTERNAL_STORAGE`, no broad
storage access, no notification and no foreground service. The vault root's persisted Storage Access
Framework grant is still the only way anything is reached, and a viewer reads only what the vault's
own index names.

Everything platform-shaped stays in the data layer: `MediaPlayer`, `BitmapFactory`, `PdfRenderer`,
`SurfaceView` and the surface's lifecycle live in adapters behind `VaultMediaEngine`,
`VaultImageEngine` and `VaultDocumentEngine`; the domain contracts and the viewer's state carry no
Android type, no bitmap, no stream, no player and no key. No new dependency was added: the platform's
own media stack, image decoder and PDF renderer are used, and nothing else was brought in.

## Security and privacy boundaries

* No key, content key, key-store object, cipher, nonce, wrapped blob or storage handle ever reaches a
  screen: the viewer's state carries facts, counts and positions, and a JVM test asserts by reflection
  that no state type could carry bytes, a stream, a decoder or a key.
* No filename, plaintext, URI, path, identifier or nonce is logged, printed or reported anywhere; the
  failures are fixed, non-secret sentences.
* No plaintext temporary file, no plaintext cache and no persistent plaintext thumbnail exists — the
  PDF path uses a proxy file descriptor instead of a file, which is the only place a platform API
  asked for one.
* No sharing, exporting, copying to Downloads, "open with" or `ACTION_SEND` exists anywhere in the
  application; decrypted content leaves through a screen and nowhere else.
* The original imported file is never touched, no vault object is deleted or modified because a viewer
  could not render it, and a missing or unlisted object is reported rather than repaired.
* No network, analytics, telemetry or usage history is involved: viewing is entirely local.

## Scope

Delivered: the content classifier, the content reader and its handle, the bounded pipe, the media data
source, the image, media and document engines, the viewer's state machine and messages, the viewer
inside the vault screen (open, controls, close, back, lifecycle), the list's type and open action, the
verifier rules and the test suites.

Deliberately **not** in this stage: albums, tags, favourites, search, sorting and any other way of
organising the list; trash, restore and permanent deletion; recovery after reinstalling, backup and
cloud sync; sharing, exporting or opening a file in another application; thumbnails and picture
caching; zoom beyond a double tap, panning, or advanced gestures; a background playback service, a
notification or a media session; and the vault's visual polish (Stage 16 onward).

## Runtime verification status

The classifier, the content reader, the handle's failure vocabulary and bounds, the engines' seams, the
viewer's state machine, its session behaviour and its wording are verified by local JVM suites — 87
tests across five suites — together with the static checks, which run on every change. The verifier's
Stage 15 rules are themselves negative-tested: each one is broken on purpose and seen to fail, and the
repository is restored byte for byte afterwards.

What is **not** verified by those suites, and is therefore not claimed anywhere: the real image
decoder, the platform's media playback (including whether a device seeks through a decrypted stream as
expected), `PdfRenderer` on a real document, Storage Access Framework reads of real files, rendering of
large files, and TalkBack traversal of the viewer. The instrumented suite covers the viewer screen's
composition — each state, its words and its controls — and is **compiled but not executed** in
continuous integration, because no device or emulator is attached. Claims about decoding, playback and
rendering remain claims about code that compiles until a device runs it.

# Stage 16: albums, search and the order of the list

## Purpose

A vault that only appends is a place files go to; this stage is what makes it a place somebody can
find things in. Files are grouped into **albums**, the list is **searched** by the facts Nivara already
holds about each file, and it can be **ordered** four ways.

All three are *organisation*. None of them touches an encrypted object, the index's facts, the viewer
or the key hierarchy: an album is a list of identifiers in a small authenticated record of its own, and
searching and sorting are derived from metadata that was already read. Organising a vault can
therefore not lose a file, change what a file is, or make one unreadable.

## What an album is

* A **title** the user typed, validated and bounded.
* A **stable identifier**: 128 random bits, lower-case hexadecimal, created once and never reused. The
  album is that identifier. A title is a label and may be changed at any time; two albums may share a
  title and remain two albums.
* A **creation time**, kept because an album list has to break ties somehow and nothing else names when
  an album appeared.
* **Membership**: an ordered, duplicate-free list of item identifiers.

An album holds *nothing else*. There is no copy of a name, a type, a size, an import time, a digest or
a content location in it: every fact about a file shown inside an album is read from the index at the
moment it is drawn. A list that copied those facts would be a second index, and the first rename would
leave the two disagreeing.

Albums are not folders. Nothing is moved, renamed or rewritten when a file joins one, and an identifier
that names nothing in the index is still a legal member.

## Creating, renaming and deleting

| Action | Requires | Effect |
| --- | --- | --- |
| Create album | A vault that opens, a readable album record (or none), the session | A new album with a fresh identifier and no members |
| Rename album | The same | The title changes; the identifier, the creation time and every member stay |
| Delete album | The same | **The album goes, and nothing else** |
| Add a member | The same | The item's identifier is appended if it is not already there |
| Remove a member | The same | The identifier is taken out of that album only |

Deleting an album deletes a list. The files it named keep their encrypted objects, their place in the
index, and their membership in every other album they were in. There is no operation in the album
contract that can delete, move, rename, re-encrypt or even read a stored file — the only deletion in
the album record's repository is the removal of the record slot it has just superseded.

Adding an item that is already in the album, and removing one that is not, each change nothing and are
reported as success: the state the caller asked for already holds, and a new generation that says the
same thing is a write that can only fail.

An album that loses its last member stays. Empty albums are allowed and deliberate — an album is
something a person made, and Nivara does not quietly tidy away things people made.

An item may be in no album, one album, or several. Belonging to an album says nothing about the item,
so nothing prevents a second membership.

## The album record (version 1)

Albums are kept in their own small authenticated record beside the index, in the same metadata area:

```
nivara.meta/
  albums.0.nva      ← one generation of the album record
  albums.1.nva      ← the other
```

```
clear header
  offset 0    "NVAO"           4 bytes   what these bytes are
  offset 4    version          1 byte
  offset 5    reserved         1 byte    must be zero
  offset 6    generation       8 bytes   big-endian
  offset 14   sealed payload             an authenticated envelope
```

The payload is the same shape — marker, version, reserved, generation, album count, then the albums —
and each album is its identifier (16 bytes), its title (2-byte length + UTF-8), its creation time
(8 bytes) and its members (2-byte count, then 16 bytes each).

The marker is **not** the index's, and the payload is sealed for a **purpose of its own**
(`EncryptionContext.VaultOrganization`). Either check would be enough, because both are authenticated;
both are kept because they answer different questions — one says what the bytes are before anyone holds
a key, the other says what they were sealed for before anything is written over them.

The decoder is bounded and strict. Every count is checked against a declared limit *before* anything is
allocated from it, every length is checked before it is read, an album title must be valid text of a
bounded size, identifiers must be exactly sixteen bytes, a record naming the same album or the same
member twice is refused, a payload whose generation disagrees with its own header is refused, text that
is not valid UTF-8 is refused rather than replaced, and trailing bytes are refused rather than ignored.
`null` never means "no albums": a record with no albums is a valid record with a count of zero, and an
absent record is a separate state from every unreadable one.

## Which key, and which purpose

The album record is sealed with **the vault's own key**, borrowed exactly as the index borrows it, and
no key of its own exists anywhere. Reading or changing albums is therefore impossible in a vault that
cannot be opened, and a build that cannot open the vault cannot see, repair or replace its albums
either. The purpose is authenticated, so an album envelope moved onto an index slot (or the reverse)
fails before anything is decrypted.

## The order that makes a change true

Every change runs under one lock, and follows exactly this order:

```
authorized → the vault opens → read the current record (never rebuild it)
   → apply the change in memory → validate the whole result
   → seal it as the next generation, for this purpose
   → write it into the slot that is not authoritative
   → read it back: authenticate, decode, compare with what was intended
   → only then prune the superseded slot
```

An interruption anywhere leaves the previous generation untouched: the new record goes into the other
slot, and the older one is removed only after its replacement has been read back and verified. A write
that fails is deleted, and a read-back that disagrees is deleted too, because a record Nivara cannot
verify is not one it may leave for the next reader to trust. A change is accepted only when it is
committed, and it is reported as committed only when it has been verified; a record that cannot be read
is never written over.

## Bounds

| Bound | Value | Why |
| --- | --- | --- |
| Albums per vault | 2 000 | The count is written in two bytes and the record has a size limit |
| Members per album | 5 000 | Keeps one album's encoding bounded before it is allocated |
| Memberships in total | 50 000 | Albums share items, so a per-album limit alone would not bound the record |
| Album title | 80 characters, 320 encoded bytes | A title is a label, not a payload |
| Record | 4 MiB | The largest album record Nivara will read |

## Search: which fields, and what it never does

A query is normalised once — Unicode-composed (NFC), case-folded, whitespace-collapsed — and then
matched against **authenticated metadata only**:

* the file's **name**,
* its **declared type** (the MIME type the index recorded, when it is a usable one),
* the **kind** Nivara classifies that type as, including the words the list shows for it
  (*image*, *picture*, *photo*; *video*, *movie*; *audio*, *sound*, *music*; *document*, *doc*;
  *file*).

On the albums surface, the same box searches **album titles**. Membership is deliberately not searched:
a search result must depend on the files themselves and not on how somebody organised them.

Search never decrypts, never opens a file, never touches storage and never reads the vault again — it
filters the metadata the screen already has. It is case-insensitive, Unicode-safe, deterministic, and
has no fuzzy matching, ranking, weighting, distance, popularity or history behind it. Asking twice
answers the same way, and the order of the results is the order of the list, filtered.

The states are kept apart, because the difference is the point:

| State | Meaning |
| --- | --- |
| Empty box | Nothing is being asked; the ordinary list is shown |
| Matches | The readable list holds files (or albums) answering the query |
| No matches | The list was read, and nothing in it answers the query |
| Cannot search | The list — or the album record — cannot be read, so the query was never asked |

"No file matches" is only ever said about a list that was actually read. "Cannot search" is never drawn
as an empty result, and an empty result is never drawn as a failure.

## Ordering

| Field | Comparison | Tie-break |
| --- | --- | --- |
| Name | Case-insensitive, then the name as written | Identifier |
| Size | Bytes | Identifier |
| Imported | Arrival time | Identifier |
| Type | Kind (pictures, video, audio, documents, other), then the name | Identifier |

Every order ends in the item's identifier, so it is *total*: two files that look identical — the same
name, size and second of arrival — still come out in the same sequence every time, and the list never
reshuffles itself. Direction is ascending or descending and applies to the whole comparison.

The default is what the vault has always shown: **newest import first**. Ordering is a display
decision, computed in memory from the metadata that was read; it never consults usage, popularity,
recency-of-viewing or anything else Nivara does not keep.

Albums are ordered by title, case-insensitively, with the album identifier as the tie-break. There is
no drag-and-drop reordering and no album ordering to store.

## Stale references, missing content and the unindexed

The index and the album record are two different things and can disagree:

* A file the index names whose encrypted object is not in the content area is still listed, and the
  list says how many such entries it holds.
* A reference an album holds that the index no longer names is **kept** and shown as *no longer in the
  vault*. Nothing removes it on its own: only an explicit "remove from album" does, and that removes
  the reference and nothing else.
* An index that cannot be read is not an album list without members. The album is drawn with its counts
  as the record holds them, and the screen says that what its members *are* cannot be shown right now.
* An album record that cannot be read is not a vault without albums. Nothing is created, renamed or
  deleted from that state, and the record is left exactly as it was found.
* Encrypted objects that no index entry names are reported by the list, are never treated as files, and
  are never deleted by anything in this stage.

## The screen

The vault screen keeps its own state card and gains three things above the list:

* **All files / Albums** — which collection is shown. Selecting a file opens it through the same viewer
  as before, whichever collection it was found in; there is exactly one way into a file.
* A **search box**, and the sentence that says what it found.
* **Order**: four fields and a direction, and the default the vault already had.

Albums are listed with their title, when they were made and what they hold. Creating one is a title and
an action; renaming happens in place; deleting asks first and says, in as many words, that the files
are not deleted. An open album shows its items in the same rows as the vault's own list, its stale
references with the one action that can remove them, and an *add or remove files* surface that offers
the files the index knows about — never a storage rescan. A file already in the album is marked as such
and cannot be added twice.

Nothing about the vault's readiness or failure states is hidden by any of this: a vault that cannot be
opened, a list that cannot be read and an album record that cannot be read are each drawn as
themselves, and none of the organising controls is offered over a state that cannot support it.

## The session, and what a change requires

Every album change requires the **existing** session. There is no album password, no second prompt, no
separate screen and no new timeout: the screen asks the session manager that already exists, and the
repository asks the same question again at the moment the record would change, so a session that ends
while somebody is typing cannot produce a change.

A change asked for without a session is refused the way every other change in the vault is refused — the
user is sent to the credential screen that already exists — and a session that closes mid-change fails
the change with the previous record untouched. Nothing in the album path establishes, refreshes or
extends a session.

Reading is unaffected: looking at the albums, searching and sorting require no session, because they
are not changes.

## Failure states

| State | What it means | What is offered |
| --- | --- | --- |
| No record yet | Nobody has created an album in this vault | Creating, and nothing else |
| Readable | The albums are there | Everything |
| Unreadable | The record exists and cannot be opened — damaged, or a key that is unavailable | Nothing; the record is left alone |
| A newer format | A newer Nivara wrote it | Nothing; it is never written over |
| Unavailable | The storage could not be reached | Nothing |
| Access denied | The platform refused access to the folder | Nothing; the folder can be chosen again |
| Vault not ready | The vault itself cannot be opened | Nothing; the vault's own state says why |

Every refused change carries a typed reason and its own sentence: a name that cannot be stored, a title
that has not changed, an album that is full, a record that is full, an album that is not there, a
missing key, a refused write, a record that did not read back as it was written. None of them is worded
as though the albums were gone.

## Permissions and platform boundaries

No new permission is requested for any of this. Albums, search and sorting use the folder the user
already chose, the session that already exists and the encryption that is already there. The domain's
organisation contracts — the album, its identifier, its ordering rules, its search and its repository —
know nothing about Android, storage, keys or Compose: no `Context`, no `Uri`, no bitmap, no platform
type of any kind. The screen draws states and reports taps, and holds no persistence, no key and no way
to read a file.

## Security and privacy boundaries

* **No second key, and no second cipher.** The album record is sealed with the vault key through the
  existing encryption service, under a purpose of its own.
* **No plaintext metadata.** Album titles and membership exist on storage only inside an authenticated
  envelope, and nowhere else.
* **No content, ever.** Search, ordering and albums read the index and nothing else. No decryption
  happens for a search, a sort, a membership change or an album title. There are no thumbnails, no
  previews and no caches anywhere in this stage.
* **No independent item database.** Albums hold identifiers and are resolved against the vault's own
  index; nothing stores a second copy of a file's facts.
* **No usage history.** Nothing records what was searched, opened, sorted or looked at, and no order or
  result is influenced by anything but the metadata of the files themselves.
* **No sharing or export.** No file can leave the vault from this screen, and there is no way to hand a
  file to another application from it.

## Scope

Delivered: the album model and identifier, the album record's codec and repository, the two-slot
generational write with a read-back before pruning, the ordering rules, the search rules and their
states, the albums surface inside the vault screen (list, create, rename, delete with confirmation,
open, add and remove members), the search box and the sort controls, the verifier rules and the test
suites.

Deliberately **not** in this stage: trash, restore and permanent deletion (Stage 17); recovery after
reinstalling, backup and cloud sync; sharing, exporting or opening a file elsewhere; favourites, tags,
usage history, recently-opened lists and anything else that would have to be recorded to be shown;
content-based search, OCR and text extraction; thumbnails, previews and persistent caches; background
work or a media service; and new permissions, a second password or a second session.

## Runtime verification status

The album model and its membership rules, the ordering rules (every field, both directions, the
tie-break and its determinism), the search rules (case, Unicode, whitespace, type, kind, album titles
and every state), the record's codec (round trips, malformed records, duplicates, truncated and
overlong records, trailing bytes and unsupported versions), the repository (creation, renaming,
deletion, membership, two-slot generations, read-back verification, refused and dropped writes,
authorization before and during a change, and every unreadable state), and the screen's state machine
and wording are verified by local JVM suites — 199 tests across seven suites — together with the static
checks, which run on every change. The verifier's Stage 16 rules are themselves negative-tested: each
one is broken on purpose and seen to fail, and the repository is restored byte for byte afterwards.

What is **not** verified by those suites, and is therefore not claimed anywhere: the writing and reading
of a real album record through the Storage Access Framework on a device, the behaviour of the album
surface on a real screen, TalkBack traversal of the new controls, and how a very large album list
behaves while it is being drawn. The instrumented suite covers the albums surface's composition — each
state, its words and its controls — and is **compiled but not executed** in continuous integration,
because no device or emulator is attached.

# Stage 17: trash, restore and what is kept

## Purpose

A vault accumulates, and at some point a file should be able to leave the screen without leaving the
vault. Trash is that: a **state**, not a place. Moving a file to Trash takes it out of the active
collection — the list, the search, the albums' contents — and restoring it puts it back. Nothing in
between touches the file itself: the encrypted object is not opened, moved, renamed, re-encrypted or
deleted, the item keeps the identifier it always had, its metadata stays where the index holds it, and
every album that names it keeps naming it.

The one thing Trash is not is a deletion. Nothing in this feature can express permanent deletion,
secure erase, emptying the trash or an expiry: the record says which files are out of sight, and the
only way out of that state is restoring. A file in the trash is as recoverable as it was before, by
the same identity, through the same vault.

## What moving to Trash means

One line is written into one small authenticated record: the item's identifier — the same one it has
while active — and the instant it left the active collection. That is the whole of the change.

The entry is deliberately not a copy of the item. No name, no type, no size, no digest, no content
location is stored beside it, because the index already holds all of that, authenticated, and a second
copy would be a second thing that could disagree. Every detail the trash list shows about a file is
read from the vault's index at the moment it is shown, exactly as it is for a file in an album.

## What restoring means

The line is removed from the record. The item's identifier is unchanged, its metadata is unchanged,
its encrypted object is unchanged and every album membership is effective again the moment it is back
in the active collection — because the memberships never went anywhere. Restoring does not claim the
file's content is present: if the encrypted object was already missing, the vault's list says so and
opening the file reports exactly what it reported before the file was trashed.

Both operations are idempotent where that is honest: moving a file that is already in the trash
changes nothing and is reported as done, and restoring a file that is not in the trash changes nothing
and is reported as done. Neither path can create a second item for one file.

## The trash record (version 1)

The record lives beside the index and the album record, built on the same pattern:

- two dedicated slots in the vault's metadata area, `trash.0.nvt` and `trash.1.nvt`;
- a clear header carrying Nivara's trash marker `NVTR`, the format version, a reserved byte and the
  generation — distinct from the index's `NVIN` and the album record's `NVAO`;
- a sealed payload holding the marker again, the version, the generation, an entry count and the
  entries themselves, each exactly an identifier and a moment.

The payload is sealed under its own purpose in the existing encryption context, tag `0x09` — the same
vault key, the same encryption service, the same authenticated envelope as everything else in the
vault. There is no second key and no second cipher; the purpose alone keeps a ciphertext made for the
index or the albums from ever being read as a trash record, and the marker says what the bytes are
before anyone holds a key.

The format is bounded and strict. A count read from untrusted bytes is a claim: it is checked against
the format's own limits before anything is allocated from it, identifiers must be exactly sixteen
bytes of lower-case hex, entries must be in strictly increasing identifier order (which makes a
duplicate impossible to express), a moment before the epoch is refused, and trailing bytes are refused
rather than ignored. One set of entries has exactly one encoding, so a read-back can be compared byte
for byte with what was intended. A record Nivara cannot read is never interpreted as an empty trash:
the decoder answers nothing at all, and the state says the record is unreadable.

The bounds: at most 20 000 entries in one record, and the sealed record itself is never read past one
megabyte. A vault that one day needs more raises the format's version rather than growing past what
it promises to read.

## The order that makes a change true

Every change follows the same protocol the album record follows, because a trash change is the same
kind of promise:

1. the session is asked, before anything is read;
2. the vault is opened — a vault that cannot be opened cannot have its trash changed;
3. for a move to trash, the vault's list is read, because the change must prove the file is there;
4. the current trash record is read, never rebuilt;
5. the item is checked to be where the change says it is;
6. the new list of entries is calculated, validated and put in canonical order;
7. it is sealed as the next generation under the trash purpose;
8. it is written into the slot that is not authoritative;
9. it is read back, authenticated, decoded and compared with what was intended;
10. only then is the superseded slot pruned — and pruning stays best-effort, because the newer
    generation already wins on the next read.

A failure anywhere leaves the previous committed record intact, and nothing is reported as done
before the read-back verifies. The session is asked again at the moment the vault would change, so a
session that ends while the person is looking at the trash cannot produce a change. Every mutation
runs under the repository's existing lock, so two changes cannot interleave their generations and a
read never lands between the write and its verification.

## Albums and trash

Album memberships survive the trash and the restore, because an album is a list of identifiers and the
trash never edits it. While a file is trashed, an album that names it reports it apart: the album's
count includes it, the album's surface never draws it as active content, and opening the album shows
the active members with the trashed one named separately. Deleting an album deletes that list and
nothing else — a trashed file is as untouched by it as an active one. An unreadable album record does
not make the trash unreadable, and an unreadable trash record does not remove anything from an album:
each record is its own fact.

## The active collection

The list the screen draws is the index's items minus the identifiers the trash record names — computed
once, before the search and before the order, so no list, result or count can disagree about what is
active. When the trash record cannot be read, Nivara cannot say which files are out of sight, and the
screen says exactly that instead of drawing the list as if every file in it were active.

## Search and order

On every surface but the trash, the search box asks about the active collection and nothing else: a
query that matches only a trashed file answers "nothing matches". On the trash surface the same box
asks about the trash alone, with the same normalization — case, Unicode form and whitespace — applied
to the file's name, type and kind as read from the index. Nothing is decrypted for it, nothing is
remembered, and there is no second engine.

The trash list has its own order: by when it was trashed, by name, by size, by arrival time or by
kind, either direction, and every comparison ends in the item's identifier. Two files trashed in the
same millisecond come out in the same sequence every time. The moment is a display order and nothing
more: nothing expires because of it, and nothing is ranked by it.

## Missing content

A file whose encrypted object is missing can still be moved to trash and back, because the state is
about the item, not the object. The list says the content is missing exactly as it said it before, the
trash row says so too, and restoring makes no promise the vault cannot keep: opening the file goes
through the same content reader and reports the same missing-content failure it always did. Metadata
is never dropped because an object is absent, and the trash never deletes, repairs or finishes
anything — unindexed and unfinished objects stay outside the item-level trash, as before.

## The screen

The vault surface has three collections: **All Items**, **Albums** and **Trash**. The trash card says
what the record says — in its own words for every state — and each row shows the file's name, type,
size and when it was moved, with one action: restore. The card says in as many words that moving a file
to Trash is not deleting it: the encrypted file stays in the vault with its name, its albums and
everything else until it is restored. There is no destructive control anywhere on the surface: no
empty-trash, no delete-forever, no expiry.

## The session, and what a change requires

Moving a file to trash and restoring it are durable changes, so they require what every other durable
change requires: an open vault, a readable trash record and the existing session, asked at the moment
of the action and again at the moment the vault would change. There is no trash password, no second
prompt, no session extension and no route around the credential screen: when the session is closed the
screen sends the person there, and nothing else.

## Failure states

Every unreadable fact keeps its own name. A trash record that cannot be opened is *unreadable*, with
its own title and its own sentence — and it is never drawn as "Nothing is in the trash", because that
is the one misreading this feature exists to prevent. A record written by a newer Nivara is reported
with its version and never written over. Unreachable storage, a revoked grant and a vault that is not
ready are each their own state. A change that failed names its reason in fixed, non-secret copy, and
the record is read again whatever happened, so the screen shows the trash that is actually committed.

## Security and privacy boundaries

The trash record is sealed by the vault's existing encryption under its own purpose; it holds no key,
no plaintext and nothing secret in the clear. No trash state reaches a screen with anything but display
facts. Nothing here adds a permission, a network call, a log line with a name or an identifier in it,
a cache, a thumbnail, a background service, or an export of any kind.

## Scope

Delivered: the trash entry, state and failure contracts; the record's codec and repository on the
two-slot generational write with a verified read-back before pruning; the join of the trash with the
index; the trash ordering; the trash surface inside the vault screen (list, restore, search, sort and
every state's own words); the active list, the search and the albums drawn with the trash subtracted;
the verifier rules and the test suites.

Deliberately **not** in this stage: permanent deletion, secure erase, emptying the trash and automatic
expiry — none of them exists anywhere in Nivara; recovery after reinstalling, backup and cloud sync;
sharing, exporting or opening a file elsewhere; favourites, tags, usage history and recently-opened
lists; content-based search, OCR and text extraction; thumbnails, previews and persistent caches;
background work or a media service; and new permissions, a second password or a second session.

## Runtime verification status

The trash entry and its join with the index, the ordering rules (every field, both directions, the
tie-break and its determinism), the record's codec (round trips, canonical order, duplicates, invalid
identifiers, moments before the epoch, impossible counts, oversized records, unsupported versions,
generation mismatches, truncated payloads and trailing bytes), the repository (trashing, restoring,
idempotence, refused sessions, sessions ending mid-change, unreadable records, records from newer
builds, refused and swallowed writes, read-back verification, two-slot generations and the previous
record surviving a failed change), and the screen's state machine and wording are verified by local
JVM suites — 99 tests across six suites — together with the static checks, which run on every change.
The verifier's Stage 17 rules are themselves negative-tested: each one is broken on purpose and seen
to fail, and the repository is restored byte for byte afterwards.

What is **not** verified by those suites, and is therefore not claimed anywhere: the writing and
reading of a real trash record through the Storage Access Framework on a device, the behaviour of the
trash surface on a real screen, TalkBack traversal of the new controls, and how a very full trash
behaves while it is being drawn. The instrumented suite covers the trash card's composition — each
state, its words and its one action — and is **compiled but not executed** in continuous integration,
because no device or emulator is attached.
