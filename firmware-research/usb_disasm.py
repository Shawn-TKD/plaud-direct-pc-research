"""Disassemble KM4 XIP region with literal-pool annotations for USB RE."""
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_LITTLE_ENDIAN
import struct, sys

FW = r"C:\Users\20195\Documents\ChatGPT\Shawn的项目\output\plaud-firmware\plaud-notepins-66055-unknown.bin"
XIP_OFF, XIP_RT = 0x19028, 0x0E000020
data = open(FW, "rb").read()

def rt2off(rt): return XIP_OFF + (rt - XIP_RT)

def pool_string(v):
    """If v points into XIP flash and looks like a string, return it."""
    off = v - XIP_RT + XIP_OFF
    if 0 <= off < len(data) - 4:
        b = data[off:off + 48]
        if b and (all(32 <= c < 127 for c in b[:4]) or b[:2] in (b"\r\n", b"\n\r")):
            end = b.find(b"\0")
            if end > 0:
                return b[:end].decode("ascii", "replace")
    return None

def disasm(start_rt, end_rt):
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    md.skipdata = True
    blob = data[rt2off(start_rt):rt2off(end_rt)]
    out = []
    for insn in md.disasm(blob, start_rt):
        line = f"0x{insn.address:08X}: {insn.mnemonic:8s} {insn.op_str}"
        # annotate literal loads
        if insn.mnemonic in ("ldr", "ldr.w") and "[pc" in insn.op_str:
            try:
                imm = int(insn.op_str.split("#")[-1].rstrip("]"), 16)
            except ValueError:
                imm = int(insn.op_str.split("#")[-1].rstrip("]"), 10)
            pc = (insn.address + 4) & ~3
            slot = pc + imm
            v = struct.unpack_from("<I", data, rt2off(slot))[0]
            note = pool_string(v)
            if note:
                line += f"   ; pool@{slot:08X} -> {note!r}"
            elif 0x0E000000 <= v < 0x0E100000:
                line += f"   ; pool@{slot:08X} -> xip:0x{v:08X}"
            elif 0x10000000 <= v < 0x10100000:
                line += f"   ; pool@{slot:08X} -> sram:{v:08X}{' (fn ptr)' if v & 1 else ''}"
            else:
                line += f"   ; =0x{v:08X}"
        out.append(line)
    return "\n".join(out)

if __name__ == "__main__":
    start = int(sys.argv[1], 16)
    end = int(sys.argv[2], 16)
    print(disasm(start, end))
