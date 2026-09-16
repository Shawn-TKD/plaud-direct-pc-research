from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path


IMAGE_SIGNATURE = b"81958711"
IMAGE_HEADER_SIZE = 0x20
SLAVE_HEADER_SIZE = 0x208


@dataclass(frozen=True)
class ImageSegment:
    name: str
    header_offset: int
    data_offset: int
    size: int
    load_address: int
    secure_boot_header: int
    sha256: str


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((count / total) * math.log2(count / total) for count in counts.values())


def find_all(data: bytes, needle: bytes, start: int, end: int) -> list[int]:
    offsets: list[int] = []
    cursor = start
    while cursor < end:
        offset = data.find(needle, cursor, end)
        if offset < 0:
            break
        offsets.append(offset)
        cursor = offset + 1
    return offsets


def extract_strings(data: bytes, minimum: int = 5) -> list[dict[str, object]]:
    pattern = re.compile(rb"[ -~]{%d,}" % minimum)
    return [
        {"offset": match.start(), "text": match.group().decode("ascii")}
        for match in pattern.finditer(data)
    ]


def parse(path: Path, output_dir: Path) -> dict[str, object]:
    data = path.read_bytes()
    if len(data) < 8:
        raise ValueError("firmware is shorter than the PLAUD split header")

    host_size, slave_size = struct.unpack_from("<II", data, 0)
    host_start = 8
    host_end = host_start + host_size
    slave_header_start = host_end
    slave_start = slave_header_start + SLAVE_HEADER_SIZE
    slave_end = slave_start + slave_size
    if slave_end != len(data):
        raise ValueError(
            f"split lengths do not cover file: calculated 0x{slave_end:x}, actual 0x{len(data):x}"
        )

    fw_version, header_count = struct.unpack_from("<II", data, host_start)
    image_id, header_length, checksum, image_length, image_offset, flash_address = (
        struct.unpack_from("<4sIIIII", data, host_start + 8)
    )
    host_payload_start = host_start + image_offset
    host_payload_end = host_payload_start + image_length
    if host_payload_end != host_end:
        raise ValueError("Realtek OTA image length does not match PLAUD host length")

    names = [
        "km0_xip",
        "km0_sram",
        "km4_xip",
        "km4_sram",
        "km4_psram",
    ]
    image_headers = find_all(data, IMAGE_SIGNATURE, host_payload_start, host_payload_end)
    segments: list[ImageSegment] = []
    extracted_dir = output_dir / "extracted"
    extracted_dir.mkdir(parents=True, exist_ok=True)

    for index, header_offset in enumerate(image_headers):
        _, size, load_address, secure_boot_header, _, _, _ = struct.unpack_from(
            "<8sII4I", data, header_offset
        )
        data_offset = header_offset + IMAGE_HEADER_SIZE
        body = data[data_offset : data_offset + size]
        name = names[index] if index < len(names) else f"image_{index}"
        (extracted_dir / f"{index:02d}-{name}-0x{load_address:08x}.bin").write_bytes(body)
        segments.append(
            ImageSegment(
                name=name,
                header_offset=header_offset,
                data_offset=data_offset,
                size=size,
                load_address=load_address,
                secure_boot_header=secure_boot_header,
                sha256=sha256(body),
            )
        )

    host_blob = data[host_start:host_end]
    slave_header = data[slave_header_start:slave_start]
    slave_blob = data[slave_start:slave_end]
    (extracted_dir / "host-realtek-ota.bin").write_bytes(host_blob)
    (extracted_dir / "slave-header.bin").write_bytes(slave_header)
    (extracted_dir / "slave-firmware.bin").write_bytes(slave_blob)

    strings = extract_strings(data)
    keywords = re.compile(
        r"rsa|chacha|poly1305|signature|bind|handshake|token|record|opus|file_transfer|"
        r"fota|ota|wifi|ble|gatt|encrypt|decrypt|public key|PLAUD\.AI",
        re.IGNORECASE,
    )
    interesting_strings = [item for item in strings if keywords.search(str(item["text"]))]
    (output_dir / "interesting-strings.txt").write_text(
        "\n".join(
            f"0x{int(item['offset']):08x} {item['text']}" for item in interesting_strings
        )
        + "\n",
        encoding="utf-8",
    )

    return {
        "source": str(path.resolve()),
        "file_size": len(data),
        "sha256": sha256(data),
        "container": {
            "host_size": host_size,
            "host_offset": host_start,
            "slave_header_size": SLAVE_HEADER_SIZE,
            "slave_header_offset": slave_header_start,
            "slave_size": slave_size,
            "slave_offset": slave_start,
        },
        "realtek_ota": {
            "firmware_version_raw": fw_version,
            "header_count": header_count,
            "image_id": image_id.decode("ascii"),
            "header_length": header_length,
            "checksum": checksum,
            "image_length": image_length,
            "image_offset": image_offset,
            "flash_address": flash_address,
            "segments": [asdict(segment) for segment in segments],
        },
        "entropy": {
            "whole_file": entropy(data),
            "host": entropy(host_blob),
            "slave_header": entropy(slave_header),
            "slave": entropy(slave_blob),
        },
        "markers": {
            "PLAUD.AI": [hex(value) for value in find_all(data, b"PLAUD.AI", 0, len(data))],
            "Plaud###": [hex(value) for value in find_all(data, b"Plaud###", 0, len(data))],
        },
        "interesting_string_count": len(interesting_strings),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse a PLAUD NotePin S host/slave OTA package")
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("analysis"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = parse(args.firmware, args.output)
    report_path = args.output / "firmware-layout.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(report_path.resolve())


if __name__ == "__main__":
    main()
