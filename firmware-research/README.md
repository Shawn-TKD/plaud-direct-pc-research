# Offline firmware research

These tools analyze a **user-supplied NotePin S V1.2.7 OTA image**. The image,
binary extracts, firmware strings dump, and vendor SDK decompilation are not
in this repository. The image analyzed for the case study had SHA-256
`03a147a3eac77e5e7899b3bbc09483f84719ff683687a620da3ccc3f087ec038`.

Use a Python virtual environment with `capstone` for the disassembly helpers.
For example:

```powershell
python analyze_firmware.py path/to/owner-supplied.bin --output analysis
python scan_thumb.py path/to/owner-supplied.bin --region km4_xip --start 0x0e013982
```

`analysis/` is generated locally and ignored by Git. The parser's layout
constants and the detailed notes apply to this NotePin S image. They should
not be applied blindly to Note Pro firmware. A separate, locally downloaded
Note Pro V1.7.0 image has now been compared in
[the Note Pro comparison](NOTE-PRO-VS-NOTEPIN-S.md). Its firmware binary and
binary extracts are also omitted from this repository.

`verify_cached_signature.py` accepts a local PC bridge state directory and
the extracted public key. It prints only key length and which candidate
message verified; it does not print the signature, SN, or private key.

See [the analysis record](REVERSE-ENGINEERING.md) for image structure,
handshake evidence, confidence boundaries, and next research steps.

## USB and WiFi fast-transfer round (2026-10-20)

[USB-WIFI-RE-20261020.md](USB-WIFI-RE-20261020.md) records a second analysis
round on the same owner-supplied image:

- `usb_disasm.py` — Capstone disassembler helper with literal-pool annotation
  (resolves XIP/SRAM pointers, function pointers, and log format strings).
- The USB plug-in handler logic, CDC-ACM/MSC (usbms) mode gating, and the
  reason a sleeping device fails enumeration with a live data path.
- The complete BLE application command dispatch table (34 commands) and the
  indexed WiFi command table (14/15/18/21).
- The WiFi fast-transfer wire format (8-byte header + JSON frames, per-frame
  ChaCha20-Poly1305 or AES-GCM with BLE-session keys, WebSocket server on the
  client side at port 8081), cross-checked against the vendor SDK shipped in
  our own app build. Decompiled vendor sources stay local and are ignored by
  Git.
