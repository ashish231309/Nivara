# Nivara encrypted envelope — format version 1

This document is the authority for the byte layout of every encrypted blob Nivara produces.
`EncryptedEnvelope` (data layer) implements it; the unit tests verify it byte for byte against
an independent implementation of the same specification.

## Byte layout

All sizes are in bytes. Multi-byte fields are big-endian. The format is fixed-length for the
header, so parsing is deterministic and allocation-free.

| Field | Offset | Size | Value in v1 |
| --- | --- | --- | --- |
| `magic` | 0 | 4 | ASCII `NIVR` (`4E 49 56 52`) |
| `version` | 4 | 1 | `0x01` |
| `keyScheme` | 5 | 1 | `0x01` — Android Keystore AES-256-GCM key (hardware-backed where the device supports it) |
| `algorithm` | 6 | 1 | `0x01` — AES-256-GCM with a 128-bit authentication tag |
| `contextTag` | 7 | 1 | Purpose tag, see below |
| `reserved` | 8 | 1 | `0x00`; must be zero in v1 |
| `nonce` | 9 | 12 | Unique random nonce, never reused under the same key |
| `ciphertext` | 21 | n | GCM ciphertext with the 16-byte authentication tag appended |

- Fixed header length: **21 bytes** (offsets 0–20, including the nonce).
- Minimum envelope length: **37 bytes** (21-byte header plus a 16-byte tag for empty plaintext).
- The authentication tag is **not stored separately**: JCA's `AES/GCM/NoPadding` appends it to
  the ciphertext, and Nivara keeps that representation.

## Authenticated additional data (AAD)

The AAD is the **first 8 bytes of the envelope** exactly as serialised:

```
magic || version || keyScheme || algorithm || contextTag
```

Every one of those bytes is also validated at parse time, so a ciphertext cannot be replayed
under a different version, key scheme, algorithm or purpose: changing any of them breaks the
tag.

## Purpose tags

| Tag | Purpose |
| --- | --- |
| `0x01` | Vault content |
| `0x02` | Vault metadata |
| `0x03` | Recovery envelope |
| `0x04` | Application security data |
| `0x05` | Key wrapping |
| `0x06` | Device-protected key material |
| `0x07` | Vault content index |

`0x00` and `0x08`–`0xFF` are reserved and rejected as unsupported in v1: the tag must name one of
the purposes above, and `fromTag` returns nothing for anything else.

## Parsing rules

A parser must reject, and never silently fall back or reinterpret:

| Condition | Failure |
| --- | --- |
| Fewer than 37 bytes | `MalformedEnvelope` |
| `magic` is not `NIVR` | `UnsupportedEnvelope` |
| `version` is not `1` | `UnsupportedVersion` |
| `keyScheme` is not `1` | `UnsupportedKeyScheme` |
| `algorithm` is not `1` | `UnsupportedAlgorithm` |
| `contextTag` names no purpose | `UnsupportedContext` |
| `reserved` is not zero | `MalformedEnvelope` |
| `contextTag` differs from the purpose the caller asked for | `ContextMismatch` |
| GCM tag does not verify (wrong key, edited nonce, ciphertext, header or AAD) | `AuthenticationFailed` |

## Streamed objects (content format 1)

Files that may be larger than memory are encrypted as a stream instead of as one envelope. The
format is `NVCO` (version 1) and it reuses the same cipher, key and purpose rules — the streaming
format is a different *framing*, not a second cryptosystem.

```
header: magic(4) | version(1) | reserved(1) | contextTag(1) | keyScheme(1) | algorithm(1)
      | chunkSize(4) | nonce(12) | identity(16)
record: plaintextLength(4) | sequence(8) | flags(1) | ciphertextLength(4) | ciphertext+tag
```

* One random 12-byte header nonce per stream. Record `i` uses `headerNonce XOR bigEndian64(i + 1)`,
  so no nonce is reused within a key and no nonce is derived from a file name, an identifier or any
  other metadata.
* Every record is sealed with AES-256-GCM under the stream's key and purpose, and its AAD covers the
  whole header, the record's own header and the flag that marks the last record. The 16-byte
  `identity` — for vault content, the item id — is part of that header, so a record cannot be moved
  from one object to another.
* The stream is complete only when a record carries `flags = 0x01` and nothing follows it. A reader
  rejects a stream that ends without that record, that carries bytes after it, or that presents a
  record out of sequence, so a truncated, reordered, duplicated or edited object is never accepted
  as a shorter or different file.
* Records are read and written in bounded pieces (64 KiB of plaintext per record), so memory does
  not depend on the size of the file.

The vault's own documentation (`docs/vault/README.md`) describes how this format is used for imported
files: object names, the index, and what an interrupted import leaves behind.

## Sealed-key container format (version 1)

Used to protect one 256-bit key with another when the wrapping key is present in memory and cannot
be used through the platform key store. Two families exist and are never interchangeable:

| Family | Magic | Purpose tag | Used by |
| --- | --- | --- | --- |
| Key wrapping | `NVKW` | `0x05` Key wrapping | `ContentKeyWrapper` |
| Recovery | `NVRK` | `0x03` Recovery envelope | `RecoveryKeyEnvelopeService` |

Byte layout (fixed length, 88 bytes):

| Field | Offset | Size | Value in v1 |
| --- | --- | --- | --- |
| `magic` | 0 | 4 | `NVKW` or `NVRK` |
| `version` | 4 | 1 | `0x01` |
| `scheme` | 5 | 1 | `0x01` — HKDF-SHA-256 keystream with an HMAC-SHA-256 tag |
| `reserved` | 6 | 1 | `0x00`; must be zero in v1 |
| `contextTag` | 7 | 1 | Purpose tag of the family |
| `nonce` | 8 | 16 | Unique random nonce, also the HKDF salt |
| `wrappedKey` | 24 | 32 | Content key XOR keystream |
| `tag` | 56 | 32 | HMAC-SHA-256 over bytes 0–55 |

Construction:

```
xorKey     = HKDF-SHA-256(IKM = wrapping key, salt = nonce, info = "<family>.xor")
macKey     = HKDF-SHA-256(IKM = wrapping key, salt = nonce, info = "<family>.mac")
wrappedKey = content key XOR xorKey
tag        = HMAC-SHA-256(macKey, magic|version|scheme|reserved|contextTag|nonce|wrappedKey)
```

Parsing rules: length must be exactly 88, the magic must match the expected family, version and
scheme must be `1`, the reserved byte must be zero, the declared purpose must match the family, and
the tag must verify in constant time. Any failure is a hard failure (`MalformedEnvelope`,
`UnsupportedEnvelope`, `UnsupportedVersion`, `UnsupportedKeyScheme`, `ContextMismatch`,
`AuthenticationFailed`).

The two families derive unrelated subkeys even from the same wrapping key and nonce, because their
HKDF info strings differ.

## Versioning

A new format is introduced by publishing a new version number with its own layout. Parsers
keep reading older versions as long as their fields are still documented here, so data written
today stays readable. Removing support for a version is a deliberate, documented decision — it
never happens implicitly because of a parse failure.
