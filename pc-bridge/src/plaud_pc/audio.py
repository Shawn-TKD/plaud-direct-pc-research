from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding

HEADER_SIZE = 512
MAGIC = b"PLAUD.AI"


@dataclass(frozen=True)
class PlaudAudioHeader:
    version: int
    header_size: int
    crc: int
    user_id: str
    file_type: int
    channels: int
    encrypt_type: int
    duration: int
    counter: int
    nonce: bytes
    segment: int
    key_cipher: bytes

    @classmethod
    def parse(cls, data: bytes) -> "PlaudAudioHeader":
        if len(data) < HEADER_SIZE or data[:8] != MAGIC:
            raise ValueError("not a PLAUD.AI encrypted audio container")
        version, header_size, crc = struct.unpack_from("<HHI", data, 8)
        if header_size != HEADER_SIZE:
            raise ValueError(f"unsupported Plaud header size: {header_size}")
        file_type, channels, encrypt_type = struct.unpack_from("<HHH", data, 48)
        duration = struct.unpack_from("<I", data, 54)[0]
        counter = struct.unpack_from("<I", data, 128)[0]
        nonce = data[132:144]
        segment = struct.unpack_from("<I", data, 144)[0]
        return cls(
            version=version,
            header_size=header_size,
            crc=crc,
            user_id=data[16:48].rstrip(b"\0").decode("utf-8", errors="replace"),
            file_type=file_type,
            channels=channels,
            encrypt_type=encrypt_type,
            duration=duration,
            counter=counter,
            nonce=nonce,
            segment=segment,
            key_cipher=data[256:512],
        )


def _chacha20(data: bytes, key: bytes, nonce: bytes, counter: int) -> bytes:
    """RFC 8439 ChaCha20 (32-bit counter, 96-bit nonce), matching ChaCha7539Engine."""
    if len(key) != 32 or len(nonce) != 12:
        raise ValueError("ChaCha20 requires a 32-byte key and 12-byte nonce")

    def rotate(value: int, bits: int) -> int:
        return ((value << bits) & 0xFFFFFFFF) | (value >> (32 - bits))

    def quarter(state: list[int], a: int, b: int, c: int, d: int) -> None:
        state[a] = (state[a] + state[b]) & 0xFFFFFFFF
        state[d] = rotate(state[d] ^ state[a], 16)
        state[c] = (state[c] + state[d]) & 0xFFFFFFFF
        state[b] = rotate(state[b] ^ state[c], 12)
        state[a] = (state[a] + state[b]) & 0xFFFFFFFF
        state[d] = rotate(state[d] ^ state[a], 8)
        state[c] = (state[c] + state[d]) & 0xFFFFFFFF
        state[b] = rotate(state[b] ^ state[c], 7)

    constants = (0x61707865, 0x3320646E, 0x79622D32, 0x6B206574)
    key_words = struct.unpack("<8I", key)
    nonce_words = struct.unpack("<3I", nonce)
    output = bytearray(len(data))
    for offset in range(0, len(data), 64):
        initial = list(constants + key_words + (counter & 0xFFFFFFFF,) + nonce_words)
        working = initial.copy()
        for _ in range(10):
            quarter(working, 0, 4, 8, 12)
            quarter(working, 1, 5, 9, 13)
            quarter(working, 2, 6, 10, 14)
            quarter(working, 3, 7, 11, 15)
            quarter(working, 0, 5, 10, 15)
            quarter(working, 1, 6, 11, 12)
            quarter(working, 2, 7, 8, 13)
            quarter(working, 3, 4, 9, 14)
        block = struct.pack(
            "<16I", *[(working[index] + initial[index]) & 0xFFFFFFFF for index in range(16)]
        )
        chunk = data[offset : offset + 64]
        output[offset : offset + len(chunk)] = bytes(
            left ^ right for left, right in zip(chunk, block)
        )
        counter = (counter + 1) & 0xFFFFFFFF
    return bytes(output)


def decrypt_plaud_audio(source: Path, target: Path, rsa_private_key_pem: str) -> PlaudAudioHeader:
    raw = source.read_bytes()
    header = PlaudAudioHeader.parse(raw[:HEADER_SIZE])
    private_key = serialization.load_pem_private_key(
        rsa_private_key_pem.encode("ascii"), password=None
    )
    symmetric_key = private_key.decrypt(header.key_cipher, padding.PKCS1v15())
    if len(symmetric_key) != 32:
        raise ValueError("decrypted Plaud audio key is not 32 bytes")
    encrypted = raw[HEADER_SIZE:]
    if header.segment:
        decrypted = b"".join(
            _chacha20(
                encrypted[offset : offset + header.segment],
                symmetric_key,
                header.nonce,
                header.counter,
            )
            for offset in range(0, len(encrypted), header.segment)
        )
    else:
        decrypted = _chacha20(encrypted, symmetric_key, header.nonce, header.counter)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    temporary.write_bytes(decrypted)
    temporary.replace(target)
    return header


def _ogg_crc(data: bytes) -> int:
    value = 0
    for byte in data:
        value ^= byte << 24
        for _ in range(8):
            value = ((value << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if value & 0x80000000 else (value << 1) & 0xFFFFFFFF
    return value


def _ogg_page(
    packet: bytes,
    *,
    serial: int,
    sequence: int,
    granule: int,
    header_type: int,
) -> bytes:
    if len(packet) >= 255:
        segments = [255] * (len(packet) // 255) + [len(packet) % 255]
    else:
        segments = [len(packet)]
    if segments[-1] == 0 and packet:
        segments.pop()
        segments.append(255)
        segments.append(0)
    header = (
        b"OggS"
        + bytes((0, header_type))
        + struct.pack("<QII", granule, serial & 0xFFFFFFFF, sequence)
        + b"\0\0\0\0"
        + bytes((len(segments),))
        + bytes(segments)
    )
    page = bytearray(header + packet)
    struct.pack_into("<I", page, 22, _ogg_crc(page))
    return bytes(page)


def wrap_raw_opus(
    source: Path,
    target: Path,
    *,
    channels: int = 1,
    frame_size: int = 80,
    sample_rate: int = 16000,
    serial: int = 0x504C4155,
) -> int:
    raw = source.read_bytes()
    if not raw or len(raw) % frame_size:
        raise ValueError("raw Plaud Opus data is not aligned to its frame size")
    packets = [raw[offset : offset + frame_size] for offset in range(0, len(raw), frame_size)]
    opus_head = (
        b"OpusHead"
        + bytes((1, channels))
        + struct.pack("<H", 312)
        + struct.pack("<IhB", sample_rate, 0, 0)
    )
    vendor = b"plaud-pc-bridge"
    opus_tags = b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)
    pages = [
        _ogg_page(opus_head, serial=serial, sequence=0, granule=0, header_type=2),
        _ogg_page(opus_tags, serial=serial, sequence=1, granule=0, header_type=0),
    ]
    for index, packet in enumerate(packets):
        pages.append(
            _ogg_page(
                packet,
                serial=serial,
                sequence=index + 2,
                granule=(index + 1) * 960,
                header_type=4 if index == len(packets) - 1 else 0,
            )
        )
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    temporary.write_bytes(b"".join(pages))
    temporary.replace(target)
    return len(packets)
