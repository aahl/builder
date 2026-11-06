"""Generate golden Noise vectors with the library ESPHome clients actually use.

The earlier hand-written reference had the same two spec deviations as the
Kotlin code, so the tests passed while every real client failed. This script
uses `noiseprotocol` (the backend aioesphomeapi runs) as the oracle:

    python3 -m pip install noiseprotocol
    python3 tools/noise_reference.py

Ephemerals are fixed so the transcript is reproducible.
"""
import base64
import sys

from noise.connection import Keypair, NoiseConnection

PROTOCOL = b"Noise_NNpsk0_25519_ChaChaPoly_SHA256"
PROLOGUE = b"NoiseAPIInit\x00\x00"
PSK = bytes(range(32))
INITIATOR_EPHEMERAL = bytes([0x11]) * 32
RESPONDER_EPHEMERAL = bytes([0x22]) * 32


def with_header(payload: bytes) -> bytes:
    return bytes((0x01, (len(payload) >> 8) & 0xFF, len(payload) & 0xFF)) + payload


def main() -> None:
    initiator = NoiseConnection.from_name(PROTOCOL)
    initiator.set_as_initiator()
    initiator.set_psks(PSK)
    initiator.set_prologue(PROLOGUE)
    initiator.set_keypair_from_private_bytes(Keypair.EPHEMERAL, INITIATOR_EPHEMERAL)
    initiator.start_handshake()

    responder = NoiseConnection.from_name(PROTOCOL)
    responder.set_as_responder()
    responder.set_psks(PSK)
    responder.set_prologue(PROLOGUE)
    responder.set_keypair_from_private_bytes(Keypair.EPHEMERAL, RESPONDER_EPHEMERAL)
    responder.start_handshake()

    message1 = initiator.write_message()
    responder.read_message(message1)
    message2 = responder.write_message()
    initiator.read_message(message2)

    # ESPHome's transport frame: 4-byte fixed header inside the ciphertext.
    inner_i2r = (1).to_bytes(2, "big") + (5).to_bytes(2, "big") + b"hello"
    inner_r2i = (2).to_bytes(2, "big") + (5).to_bytes(2, "big") + b"hello"
    frame_i2r = with_header(initiator.encrypt(inner_i2r))
    frame_r2i = with_header(responder.encrypt(inner_r2i))

    print("psk            =", PSK.hex())
    print("e1_priv        =", INITIATOR_EPHEMERAL.hex())
    print("e2_priv        =", RESPONDER_EPHEMERAL.hex())
    print("msg1           =", message1.hex())
    print("msg2           =", message2.hex())
    print("i2r_frame      =", frame_i2r.hex())
    print("r2i_frame      =", frame_r2i.hex())
    print("psk_base64     =", base64.b64encode(PSK).decode())


if __name__ == "__main__":
    sys.exit(main())
