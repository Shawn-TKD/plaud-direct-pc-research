from __future__ import annotations

import argparse
from pathlib import Path

from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs


REGIONS = {
    "km4_xip": (0x19048, 0xDCA78, 0x0E000020),
    "km4_sram": (0xDCA98, 0xF9B98, 0x10005000),
    "km0_xip": (0x48, 0x16308, 0x0C000020),
    "km0_sram": (0x16328, 0x18688, 0x00083000),
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Disassemble or search NotePin S Thumb firmware")
    parser.add_argument("firmware", type=Path)
    parser.add_argument("--region", choices=REGIONS, default="km4_xip")
    parser.add_argument("--start", type=lambda value: int(value, 0))
    parser.add_argument("--length", type=lambda value: int(value, 0), default=0x100)
    parser.add_argument("--contains", action="append", default=[])
    args = parser.parse_args()

    data = args.firmware.read_bytes()
    file_start, file_end, load_address = REGIONS[args.region]
    blob = data[file_start:file_end]
    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    disassembler.skipdata = True

    if args.start is not None:
        offset = args.start - load_address
        blob = blob[offset : offset + args.length]
        load_address = args.start

    needles = [needle.lower() for needle in args.contains]
    for instruction in disassembler.disasm(blob, load_address):
        line = f"0x{instruction.address:08x}: {instruction.mnemonic:<8} {instruction.op_str}".rstrip()
        if not needles or any(needle in line.lower() for needle in needles):
            print(line)


if __name__ == "__main__":
    main()
