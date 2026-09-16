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
not be applied blindly to Note Pro firmware. The Note Pro V1.7.0 image was
not available in the analyzed corpus; a live Note Pro signature did verify
with the PLAUD public key extracted from the NotePin S image.

`verify_cached_signature.py` accepts a local PC bridge state directory and
the extracted public key. It prints only key length and which candidate
message verified; it does not print the signature, SN, or private key.

See [the analysis record](REVERSE-ENGINEERING.md) for image structure,
handshake evidence, confidence boundaries, and next research steps.
