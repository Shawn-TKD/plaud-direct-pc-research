from __future__ import annotations

import base64
import math
import struct
from dataclasses import dataclass
from typing import Iterable

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

CMD_PRE_SIGNATURE = 65040
CMD_PRE_SIGNATURE_CONFIRM = 65041
CMD_PRE_RSA = 65042
CMD_PRE_SIGNATURE_FORCE_CLEAR = 65056


class ProtocolError(RuntimeError):
    pass


@dataclass(frozen=True)
class FileDataFrame:
    session_id: int
    offset: int
    data: bytes


def parse_file_data_body(body: bytes, *, port_version: int) -> FileDataFrame:
    """Parse a decrypted port-2 recording data frame."""
    if not body or body[0] != 2:
        raise ProtocolError("not a file-data frame")
    cursor = 1
    if port_version >= 7:
        if len(body) < cursor + 4:
            raise ProtocolError("file-data frame has no session id")
        session_id = struct.unpack_from("<I", body, cursor)[0]
        cursor += 4
    else:
        session_id = 0
    if len(body) < cursor + 5:
        raise ProtocolError("short file-data frame")
    offset = struct.unpack_from("<I", body, cursor)[0]
    cursor += 4
    length = body[cursor]
    cursor += 1
    if len(body) - cursor < length:
        raise ProtocolError("truncated file-data payload")
    return FileDataFrame(session_id, offset, body[cursor : cursor + length])


def normalize_handshake_token(value: str) -> str:
    """Mirror NiceBuildSdk.parseUserIdFromJWT() before building CMD 1."""
    token = value
    if token.startswith("client_user_"):
        token = token[len("client_user_") :]
    return token.replace("-", "")


def pre_handshake_chunks(command: int, data: bytes, chunk_size: int = 100) -> list[bytes]:
    """Build `[cmd:u16 LE][count:u8][index:u8][data]` pre-handshake packets."""
    if command not in {CMD_PRE_SIGNATURE, CMD_PRE_RSA, CMD_PRE_SIGNATURE_FORCE_CLEAR}:
        raise ValueError(f"unsupported pre-handshake command: {command}")
    if not data:
        raise ValueError("pre-handshake data must not be empty")
    if not 1 <= chunk_size <= 251:
        raise ValueError("chunk_size must leave room for the four-byte header")
    count = math.ceil(len(data) / chunk_size)
    if count > 255:
        raise ValueError("too many chunks")
    return [
        struct.pack("<HBB", command, count, index) + data[index * chunk_size : (index + 1) * chunk_size]
        for index in range(count)
    ]


def signature_chunks(signature_b64: str, force_clear: bool = False) -> list[bytes]:
    command = CMD_PRE_SIGNATURE_FORCE_CLEAR if force_clear else CMD_PRE_SIGNATURE
    return pre_handshake_chunks(command, base64.b64decode(signature_b64, validate=True))


def rsa_public_key_chunks(public_key_pem: str) -> list[bytes]:
    return pre_handshake_chunks(CMD_PRE_RSA, public_key_pem.encode("ascii"))


def assemble_pre_handshake(frames: Iterable[bytes], expected_command: int) -> bytes:
    parts: dict[int, bytes] = {}
    expected_count: int | None = None
    for frame in frames:
        if len(frame) < 4:
            raise ProtocolError("pre-handshake frame is too short")
        command, count, index = struct.unpack_from("<HBB", frame)
        if command != expected_command:
            raise ProtocolError(f"unexpected command {command}, expected {expected_command}")
        if count == 0 or index >= count:
            raise ProtocolError("invalid chunk count/index")
        if expected_count is not None and count != expected_count:
            raise ProtocolError("chunk count changed within one response")
        expected_count = count
        if index in parts and parts[index] != frame[4:]:
            raise ProtocolError("conflicting duplicate chunk")
        parts[index] = frame[4:]
    if expected_count is None or len(parts) != expected_count:
        raise ProtocolError("incomplete chunk set")
    return b"".join(parts[index] for index in range(expected_count))


def build_handshake_frame(
    bind_token: str,
    *,
    ble_version: int = 1,
    app_verify: int = 0,
    port_version: int = 20,
) -> bytes:
    """Build the plaintext CMD 1 frame; encrypt it after the RSA pre-handshake."""
    width = 32 if port_version >= 9 else 16
    token = normalize_handshake_token(bind_token).encode("ascii", errors="strict")[:width].ljust(width, b"0")
    # BaseReqPkgBean.packHead() is [protocol_type=1][cmd:u16 LE].
    return struct.pack("<BHBBB", 1, 1, 2, ble_version & 0xFF, app_verify & 0xFF) + token


@dataclass
class SessionCrypto:
    key: bytes
    nonce: bytes
    aad: bytes
    send_sequence: int = 0
    receive_sequence: int = -1

    @classmethod
    def from_rsa_response(cls, frames: Iterable[bytes], private_key_pem: str) -> "SessionCrypto":
        encrypted = assemble_pre_handshake(frames, CMD_PRE_RSA)
        private_key = serialization.load_pem_private_key(private_key_pem.encode("ascii"), password=None)
        secret = private_key.decrypt(encrypted, padding.PKCS1v15())
        if len(secret) < 56 + 16:
            raise ProtocolError("RSA session material is too short")
        result = cls(secret[:32], secret[32:44], secret[44:56])
        challenge = ChaCha20Poly1305(result.key).decrypt(result.nonce, secret[56:], result.aad)
        if challenge != b"PLAUD.AI":
            raise ProtocolError("device challenge did not authenticate")
        return result

    def encrypt_body(self, body: bytes) -> bytes:
        """Encrypt `[sequence:u32 LE] + body` exactly as the Android SDK does."""
        self.send_sequence = (self.send_sequence + 1) & 0xFFFFFFFF
        plaintext = struct.pack("<I", self.send_sequence) + body
        return ChaCha20Poly1305(self.key).encrypt(self.nonce, plaintext, self.aad)

    def encrypt_command(self, command: int, payload: bytes = b"", protocol_type: int = 1) -> bytes:
        return self.encrypt_body(struct.pack("<BH", protocol_type, command) + payload)

    def decrypt_packet(self, packet: bytes) -> tuple[int, bytes]:
        plaintext = ChaCha20Poly1305(self.key).decrypt(self.nonce, packet, self.aad)
        if len(plaintext) < 4:
            raise ProtocolError("decrypted packet has no sequence number")
        sequence = struct.unpack_from("<I", plaintext)[0]
        if sequence <= self.receive_sequence:
            raise ProtocolError("duplicate or out-of-order receive sequence")
        self.receive_sequence = sequence
        return sequence, plaintext[4:]

    def decrypt_command(self, packet: bytes) -> tuple[int, int, bytes]:
        sequence, body = self.decrypt_packet(packet)
        if len(body) < 3 or body[0] != 1:
            raise ProtocolError("not a command packet")
        return sequence, struct.unpack_from("<H", body, 1)[0], body[3:]
