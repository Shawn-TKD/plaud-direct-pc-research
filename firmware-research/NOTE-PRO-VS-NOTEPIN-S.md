# Note Pro V1.7.0 vs NotePin S V1.2.7 firmware

Static comparison of two owner-obtained PLAUD OTA downloads on 2026-09-16.
Neither binary is redistributed in this repository, and neither was installed
or modified during this analysis. The versions below are decoded from the
server's version codes; the API did not populate `version_number`.

| Observation | NotePin S | Note Pro |
| --- | --- | --- |
| Server type and version code | `notepins`, `66055` (`0x10207`, V1.2.7) | `notepro`, `67328` (`0x10700`, V1.7.0) |
| Download size | 1,145,048 bytes | 7,632,016 bytes (about 6.7 times larger) |
| Local SHA-256 | `03a147a3eac77e5e7899b3bbc09483f84719ff683687a620da3ccc3f087ec038` | `493afef5c7efe30fefd26c686e4537493ec652001eb8174e8082cf3aceb23a2e` |
| Outer layout | 8-byte size pair, 1,024,032-byte AmebaD `OTA1` host, 520-byte slave header, 120,488-byte slave image | `PLAUD.AI` header and `RTL8773DO` marker; approximately 6,380,304-byte main portion followed by a 1,251,200-byte embedded `OTA1` image |
| Trailing metadata | 512-byte `PLAUD.AI` area at end of slave image | 512-byte `PLAUD.AI` trailer after embedded `OTA1` |
| Apparent functional split | AmebaD host handles recorder, authenticated BLE commands and Wi-Fi; small slave image contains most `FMNA`/Find My strings | RTL8773DO main portion contains BLE, recorder, Find My and application Wi-Fi control; embedded AmebaD-style `OTA1` appears to be the Wi-Fi modem image |

The Note Pro split is backed by its firmware logs: `split firmware to cpu and
wifi` and `upgrade wifi modem dfu`. Its `OTA1` marker is at file offset
6,380,312, eight bytes into the embedded section. The embedded image-length
field places its end exactly at offset 7,631,504, the start of the 512-byte
footer. `RTL8711`/Ameba strings occur in this embedded section. The platform
marker `RTL8773DO` is present in the main section. Realtek itself describes
RTL8773DO as a Bluetooth audio SoC. The precise chips and electrical topology
cannot be proven from this OTA alone; the roles above are the best-supported
interpretation of the package and log messages.

The NotePin S layout is fully parsed by `analyze_firmware.py` and described in
`REVERSE-ENGINEERING.md`. Its host is an AmebaD KM0/KM4 OTA image. The slave
has `FMNA`, Find My token management and SPI handshake strings; the host logs
explicitly say it splits FOTA into host and slave. The Note Pro binary must not
be passed to that NotePin S-specific parser unchanged.

## Authentication: shared trust root, separate device identities

Both images contain the same PLAUD RSA-2048 SN-signature verification public
key. Its SubjectPublicKeyInfo DER SHA-256 is
`0236fec56232de865f362217e7a5c5d06ec826c7751de6e92a9c0eb6ace5919a`.
The Note Pro image contains two copies of this key, plus two copies of another
RSA-2048 public key with SPKI fingerprint
`2e23ed6f371d3d1c3d166b5cbb2da1dfc3e7373a0de173ca6832bbfd733f515c`.
The second key's role is not established here.

Note Pro firmware also has explicit `verify sn signature success/failed`, RSA,
and `mmi_ble_send_chacha_key_pack` / ChaCha20-Poly1305 messages. NotePin S
firmware has the corresponding RSA/public-key persistence and ChaCha session
code, with command dispatch analyzed in `REVERSE-ENGINEERING.md`. This agrees
with the owner-side runtime observation that the same Windows BLE handshake
implementation reconnected to Note Pro without a live JWT. It does **not**
mean the NotePin S signature can authenticate a Note Pro: the signed message
is each device's own raw serial number. Nor does the shared public key enable
anyone to create signatures; PLAUD's private signing key is not in the images.

The outer hardware and OTA packaging changed substantially, while the observed
SN-signature trust root and RSA/ChaCha BLE session design remained compatible.
We have not established byte-for-byte command parity for all model-specific
operations.

## Other feature evidence and limits

- Both images contain Wi-Fi and WebSocket transfer paths. Note Pro's main image
  includes logs for Wi-Fi AP connections, WebSocket commands and Wi-Fi OTA;
  NotePin S has related transfer code in its AmebaD host. Static presence does
  not prove that our PC client can complete either model's Wi-Fi fast-transfer
  handshake. That still needs a live, read-only transfer trace.
- Both contain Find My/FMNA code, but it is concentrated in the NotePin S
  slave image and in the Note Pro main image. This is a partitioning difference,
  not evidence that Find My behaviour is identical.
- Both have Opus/Ogg-related data. `OpusHead` and `OpusTags` strings occur in the
  Note Pro image but were not found in the NotePin S image. This suggests a
  different audio software build; it does not establish the format of a Note
  Pro recording on storage. No Note Pro recording was downloaded in this test.
- The Note Pro image is much larger and contains duplicated application strings
  and keys. Multiple image slots are plausible, but their exact purpose and
  update semantics have not been proven. No flashing, forced reset, or
  destructive authentication command was used.

For the PC client, the BLE identity/session layer can stay shared. Firmware
parsing, OTA installation, and Wi-Fi transport should remain model-specific
until their live behaviour is verified. The next useful experiment is one
short Note Pro recording downloaded over BLE, followed by a read-only Wi-Fi
transfer trace if the user enables it.

## References

- Realtek [AmebaD OTA documentation](https://github.com/Ameba-AIoT/ameba-rtos-matter/blob/main/tools/ota/README.md)
- Realtek [RTL8773DO product context](https://www.realtek.com/Product/ProductHitsDetail?id=4600&lang=zh-TW&menu_id=419)
- PLAUD [public embedded SDK](https://github.com/Plaud-AI/plaud-sdk-public)
