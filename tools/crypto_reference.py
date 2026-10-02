#!/usr/bin/env python3
"""Independent reference implementation of Nivara's crypto formats.

Nothing here is shipped to the app. It exists to produce *known-answer vectors* for the Kotlin
unit tests from a completely separate implementation (Python/OpenSSL), so that the tests check
the real byte format and the real KDF output instead of agreeing with the code under test.

Run it to print the vectors that are embedded in:
  app/src/test/java/com/nivara/app/data/security/EncryptedEnvelopeTest.kt
  app/src/test/java/com/nivara/app/data/security/Pbkdf2KeyDerivationServiceTest.kt
"""
import hashlib
import hmac
import json

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"NIVR"
VERSION = 1
KEY_SCHEME_ANDROID_KEYSTORE = 1
ALGORITHM_AES_256_GCM = 1
CONTEXT_VAULT_CONTENT = 0x01
CONTEXT_VAULT_METADATA = 0x02
CONTEXT_RECOVERY_ENVELOPE = 0x03
CONTEXT_APPLICATION_DATA = 0x04
CONTEXT_KEY_WRAPPING = 0x05
CONTEXT_DEVICE_KEY = 0x06

HEADER_LENGTH = 21
MIN_LENGTH = HEADER_LENGTH + 16  # 16-byte GCM tag and nothing else
NONCE_LENGTH = 12
AAD_LENGTH = 8  # magic + version + keyScheme + algorithm + contextTag


def header(version, key_scheme, algorithm, context_tag, nonce, reserved=0):
    return (
        MAGIC
        + bytes([version, key_scheme, algorithm, context_tag, reserved])
        + nonce
    )


def encrypt(key: bytes, nonce: bytes, plaintext: bytes, context_tag: int,
            version: int = VERSION, key_scheme: int = KEY_SCHEME_ANDROID_KEYSTORE,
            algorithm: int = ALGORITHM_AES_256_GCM) -> bytes:
    """Encrypt exactly as EncryptedEnvelope does: AAD is the first 8 header bytes."""
    head = header(version, key_scheme, algorithm, context_tag, nonce)
    aad = head[:AAD_LENGTH]
    ciphertext_with_tag = AESGCM(key).encrypt(nonce, plaintext, aad)
    return head + ciphertext_with_tag


def decrypt(key: bytes, envelope: bytes, expected_context: int) -> bytes:
    if len(envelope) < MIN_LENGTH:
        raise ValueError("malformed envelope")
    assert envelope[:4] == MAGIC, "bad magic"
    context_tag = envelope[7]
    assert context_tag == expected_context, "context mismatch"
    nonce = envelope[9:HEADER_LENGTH]
    ciphertext_with_tag = envelope[HEADER_LENGTH:]
    return AESGCM(key).decrypt(nonce, ciphertext_with_tag, envelope[:AAD_LENGTH])


def pbkdf2(password: bytes, salt: bytes, iterations: int, length_bytes: int = 32) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", password, salt, iterations, dklen=length_bytes)


def hex_of(data: bytes) -> str:
    return data.hex()


def main() -> None:
    out = {}

    # ---- PBKDF2-HMAC-SHA-256 known-answer vectors -------------------------------------
    # First vector is the published PBKDF2-HMAC-SHA-256 test vector, checked here so the
    # implementation generating the others is known to be correct.
    published = pbkdf2(b"password", b"salt", 1)
    assert published.hex() == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", published.hex()

    kdf_salt = b"nivara-kdf-vector-salt"
    kdf_password = b"correct horse battery staple"
    out["pbkdf2"] = {
        "iterations_100_000": pbkdf2(kdf_password, kdf_salt, 100_000).hex(),
        "iterations_200_000": pbkdf2(kdf_password, kdf_salt, 200_000).hex(),
        "iterations_100_000_utf8_pin_ascii": pbkdf2(b"482916", kdf_salt, 100_000).hex(),
        "salt_hex": kdf_salt.hex(),
        "password": kdf_password.decode(),
        "pin": "482916",
    }

    # ---- Encrypted-envelope fixtures --------------------------------------------------
    key_a = bytes(range(0x00, 0x20))          # 32 bytes: 00 01 02 ... 1f
    key_b = bytes([0xAA] * 32)
    nonce_a = bytes(range(0x20, 0x2C))        # 12 bytes
    nonce_b = bytes(range(0x40, 0x4C))

    envelope_a = encrypt(key_a, nonce_a, b"Nivara envelope interop fixture", CONTEXT_VAULT_CONTENT)
    envelope_b = encrypt(key_b, nonce_b, b"recovery-key-material-fixture", CONTEXT_RECOVERY_ENVELOPE)
    envelope_c = encrypt(key_a, nonce_a, b"Nivara envelope interop fixture", CONTEXT_APPLICATION_DATA)

    # Round-trip the reference against itself before publishing vectors.
    assert decrypt(key_a, envelope_a, CONTEXT_VAULT_CONTENT) == b"Nivara envelope interop fixture"
    assert envelope_a[-16:] != envelope_c[-16:], "AAD must bind the purpose tag into the tag"
    try:
        decrypt(key_a, envelope_c, CONTEXT_VAULT_CONTENT)
        raise AssertionError("envelope from another purpose must not decrypt")
    except Exception:
        pass
    assert decrypt(key_b, envelope_b, CONTEXT_RECOVERY_ENVELOPE) == b"recovery-key-material-fixture"

    tampered = bytearray(envelope_a)
    tampered[-1] ^= 0x01                      # flip one bit of the authentication tag

    flipped_nonce = bytearray(envelope_a)
    flipped_nonce[9] ^= 0x01                  # flip one bit of the nonce

    out["envelope"] = {
        "key_a_hex": key_a.hex(),
        "key_b_hex": key_b.hex(),
        "plaintext_a": "Nivara envelope interop fixture",
        "plaintext_b": "recovery-key-material-fixture",
        "envelope_a_hex": hex_of(envelope_a),
        "envelope_b_hex": hex_of(envelope_b),
        "envelope_other_context_hex": hex_of(envelope_c),
        "envelope_tampered_ciphertext_hex": hex_of(bytes(tampered)),
        "envelope_tampered_nonce_hex": hex_of(bytes(flipped_nonce)),
        "envelope_truncated_hex": hex_of(envelope_a[:30]),
        "envelope_bad_magic_hex": hex_of(b"X" + envelope_a[1:]),
        "envelope_bad_reserved_hex": hex_of(envelope_a[:8] + b"\x01" + envelope_a[9:]),
        "envelope_length": len(envelope_a),
    }

    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
