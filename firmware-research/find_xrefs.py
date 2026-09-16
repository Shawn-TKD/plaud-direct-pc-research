from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs


XIP_FILE_OFFSET = 0x19048
XIP_LOAD_ADDRESS = 0x0E000020
XIP_END = 0xDCA78
SRAM_FILE_OFFSET = 0xDCA98
SRAM_LOAD_ADDRESS = 0x10005000
SRAM_END = 0xF9B98


def c_string(data: bytes, offset: int) -> str:
    end = data.find(b"\0", offset)
    if end < 0:
        end = len(data)
    return data[offset:end].decode("ascii", "replace")


def t1_literal_references(blob: bytes, base_address: int, slot: int) -> list[int]:
    references: list[int] = []
    for offset in range(0, len(blob) - 2, 2):
        halfword = struct.unpack_from("<H", blob, offset)[0]
        if halfword & 0xF800 != 0x4800:
            continue
        effective_address = ((base_address + offset + 4) & ~3) + (halfword & 0xFF) * 4
        if effective_address == slot:
            references.append(base_address + offset)
    return references


def disassembly_context(
    disassembler: Cs, blob: bytes, base_address: int, instruction_address: int
) -> list[str]:
    offset = instruction_address - base_address
    start = max(0, offset - 32)
    end = min(len(blob), offset + 16)
    return [
        f"0x{insn.address:08x}: {insn.mnemonic} {insn.op_str}".rstrip()
        for insn in disassembler.disasm(blob[start:end], base_address + start)
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description="Find ARM Thumb literal xrefs to firmware strings")
    parser.add_argument("firmware", type=Path)
    parser.add_argument("offsets", nargs="+", type=lambda value: int(value, 0))
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    data = args.firmware.read_bytes()
    regions = [
        ("km4_xip", data[XIP_FILE_OFFSET:XIP_END], XIP_FILE_OFFSET, XIP_LOAD_ADDRESS),
        ("km4_sram", data[SRAM_FILE_OFFSET:SRAM_END], SRAM_FILE_OFFSET, SRAM_LOAD_ADDRESS),
    ]
    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    results: list[dict[str, object]] = []

    for file_offset in args.offsets:
        runtime_address = XIP_LOAD_ADDRESS + file_offset - XIP_FILE_OFFSET
        entry: dict[str, object] = {
            "string_file_offset": file_offset,
            "string_runtime_address": runtime_address,
            "string": c_string(data, file_offset),
            "pointer_slots": [],
        }
        needle = struct.pack("<I", runtime_address)
        for region_name, blob, region_file_offset, region_address in regions:
            cursor = 0
            while True:
                pointer_offset = blob.find(needle, cursor)
                if pointer_offset < 0:
                    break
                pointer_address = region_address + pointer_offset
                refs = t1_literal_references(blob, region_address, pointer_address)
                slot = {
                    "region": region_name,
                    "file_offset": region_file_offset + pointer_offset,
                    "runtime_address": pointer_address,
                    "references": [
                        {
                            "address": ref,
                            "context": disassembly_context(
                                disassembler, blob, region_address, ref
                            ),
                        }
                        for ref in refs
                    ],
                }
                entry["pointer_slots"].append(slot)  # type: ignore[union-attr]
                cursor = pointer_offset + 1
        results.append(entry)

    output = json.dumps(results, indent=2)
    if args.json:
        args.json.write_text(output, encoding="utf-8")
    sys.stdout.write(output + "\n")


if __name__ == "__main__":
    main()
