from __future__ import annotations

import argparse
import re
from pathlib import Path


def words(data: bytes, base: int, count: int) -> None:
    for offset in range(0, count * 4, 4):
        value = int.from_bytes(data[offset : offset + 4], "little")
        print(f"0x{base + offset:08x}: 0x{value:08x}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("firmware", type=Path)
    args = parser.parse_args()
    data = args.firmware.read_bytes()
    slave_start = 0xFA230
    footer_start = len(data) - 0x200
    print("slave first words")
    words(data[slave_start : slave_start + 0x80], slave_start, 32)
    print("footer first words")
    words(data[footer_start : footer_start + 0x40], footer_start, 16)
    for term in [
        b"nRF",
        b"Nordic",
        b"Zephyr",
        b"FreeRTOS",
        b"CMSIS",
        b"SoftDevice",
        b"FMNA",
        b"Find My",
        b"RTL",
        b"Bouffalo",
        b"Telink",
    ]:
        offsets = [slave_start + match.start() for match in re.finditer(re.escape(term), data[slave_start:])]
        print(term.decode("ascii"), [f"0x{offset:x}" for offset in offsets[:20]])


if __name__ == "__main__":
    main()
