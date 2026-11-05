"""Reference NNpsk0 handshake used to produce golden vectors for the Kotlin tests.

Implements the Noise spec (rev 34) with ESPHome's prologue and framing so the
Kotlin responder can be compared byte for byte.
"""
import hashlib
import hmac
import sys

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

PROTOCOL_NAME = b"Noise_NNpsk0_25519_ChaChaPoly_SHA256"
HASHLEN = 32


def hkdf(ck: bytes, ikm: bytes, n: int) -> list[bytes]:
    temp = hmac.new(ck, ikm, hashlib.sha256).digest()
    out, prev = [], b""
    for i in range(1, n + 1):
        prev = hmac.new(temp, prev + bytes([i]), hashlib.sha256).digest()
        out.append(prev)
    return out


def dh(priv: bytes, pub: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(priv).exchange(X25519PublicKey.from_public_bytes(pub))


class CipherState:
    def __init__(self, key: bytes | None):
        self.k = key
        self.n = 0

    def encrypt(self, plaintext: bytes, aad: bytes) -> bytes:
        if self.k is None:
            return plaintext
        nonce = b"\x00" * 4 + self.n.to_bytes(8, "little")
        self.n += 1
        return ChaCha20Poly1305(self.k).encrypt(nonce, plaintext, aad)

    def decrypt(self, ciphertext: bytes, aad: bytes) -> bytes:
        if self.k is None:
            return ciphertext
        nonce = b"\x00" * 4 + self.n.to_bytes(8, "little")
        self.n += 1
        return ChaCha20Poly1305(self.k).decrypt(nonce, ciphertext, aad)


class SymmetricState:
    def __init__(self, prologue: bytes):
        h = hashlib.sha256(PROTOCOL_NAME).digest()
        self.ck = h
        self.h = h
        self.mix_hash(h)
        self.mix_hash(prologue)
        self.cipher = CipherState(None)

    def mix_hash(self, data: bytes) -> None:
        self.h = hashlib.sha256(self.h + data).digest()

    def mix_key(self, ikm: bytes) -> None:
        self.ck, k = hkdf(self.ck, ikm, 2)
        self.cipher = CipherState(k)

    def mix_key_and_hash(self, ikm: bytes) -> None:
        self.ck, temp_h, temp_k = hkdf(self.ck, ikm, 3)
        self.mix_hash(temp_h)
        self.cipher = CipherState(temp_k)

    def encrypt_and_hash(self, plaintext: bytes) -> bytes:
        ciphertext = self.cipher.encrypt(plaintext, self.h)
        self.mix_hash(ciphertext)
        return ciphertext

    def decrypt_and_hash(self, ciphertext: bytes) -> bytes:
        plaintext = self.cipher.decrypt(ciphertext, self.h)
        self.mix_hash(ciphertext)
        return plaintext

    def split(self):
        k1, k2 = hkdf(self.ck, b"", 2)
        return CipherState(k1), CipherState(k2)


def handshake(psk: bytes, prologue: bytes, e1_priv: bytes, e2_priv: bytes):
    s = SymmetricState(prologue)
    e1_pub = X25519PrivateKey.from_private_bytes(e1_priv).public_key().public_bytes_raw()

    # msg1: psk, e
    s.mix_key_and_hash(psk)
    s.mix_hash(e1_pub)
    msg1 = e1_pub + s.encrypt_and_hash(b"")

    # msg2: e, ee
    e2_pub = X25519PrivateKey.from_private_bytes(e2_priv).public_key().public_bytes_raw()
    s.mix_hash(e2_pub)
    s.mix_key(dh(e2_priv, e1_pub))
    msg2 = e2_pub + s.encrypt_and_hash(b"")

    c1, c2 = s.split()
    return msg1, msg2, c1, c2, s.h


def main() -> None:
    psk = bytes(range(32))
    e1_priv = bytes([0x11]) * 32
    e2_priv = bytes([0x22]) * 32
    prologue = b"NoiseAPIInit\x00\x00"

    msg1, msg2, c1, c2, h = handshake(psk, prologue, e1_priv, e2_priv)

    # One transport frame each way: ESPHome's inner header is 4 bytes big endian.
    frame = b"\x00\x01" + (0x0005).to_bytes(2, "big") + b"hello"
    transport_i2r = c1.encrypt(frame, b"")
    transport_r2i = c2.encrypt(frame, b"")

    print("psk            =", psk.hex())
    print("e1_priv        =", e1_priv.hex())
    print("e2_priv        =", e2_priv.hex())
    print("e1_pub         =", X25519PrivateKey.from_private_bytes(e1_priv).public_key().public_bytes_raw().hex())
    print("e2_pub         =", X25519PrivateKey.from_private_bytes(e2_priv).public_key().public_bytes_raw().hex())
    print("handshake_hash =", h.hex())
    print("msg1           =", msg1.hex())
    print("msg2           =", msg2.hex())
    print("i2r_frame      =", transport_i2r.hex())
    print("r2i_frame      =", transport_r2i.hex())


if __name__ == "__main__":
    sys.exit(main())
