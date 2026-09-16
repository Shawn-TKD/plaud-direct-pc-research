# What the BLE protocol and firmware establish

## Evidence levels

**Observed on the Note Pro:** Android SDK obtained RSA material and a signed SN;
the device accepted its encrypted handshake and local bind; the developer
portal accepted the cloud bind. Windows imported that same identity and
completed two fresh, direct BLE sessions, reading status and the file list.

**Observed in the NotePin S V1.2.7 firmware:** the host image contains BLE
pre-handshake command dispatch, a PLAUD RSA-2048 verification public key,
client RSA public-key persistence, and session-key packaging. That public key
also verified the Note Pro signature over its raw SN. This is cross-model
cryptographic evidence, but it does not make the NotePin S binary a Note Pro
firmware image.

**Observed in the Note Pro V1.7.0 firmware:** the same PLAUD SN-verification
public key, explicit SN signature-verification messages, and RSA/ChaCha session
code. Its RTL8773DO main image and embedded Wi-Fi OTA layout differ markedly
from NotePin S. See the [model comparison](../../firmware-research/NOTE-PRO-VS-NOTEPIN-S.md).

**Not yet tested:** using a new, locally generated RSA pair with the same
signature on a freshly unbound Note Pro; the prepare-only Android path; offline
reconnect after months or a firmware update; Note Pro recording download (its
file list was empty); Windows Wi-Fi fast transfer.

## Handshake state machine

```text
User JWT --(PLAUD API)--> SN signature + RSA pair
                                  |
PC/Android -- BLE GATT 0x1910 --> device
             0x2BB1 writes / 0x2BB0 notifications
             PRE_HANDSHAKE (65040) with SN signature
             RSA_PUBLIC_KEY (65042), if not already enrolled
device ------ RSA-wrapped fresh session material ------> client
             32-byte ChaCha key + 12-byte nonce + 12-byte AAD
             encrypted PLAUD.AI challenge
client ------ encrypted CMD 1 bind identity -----------> device
             encrypted status, file-list, transfer commands
```

The embedded PLAUD public key verifies the SN signature locally. The private
signing key is not in the firmware, and this research does not provide a way to
mint new PLAUD signatures without the official partner service. The signature
we tested is RSA PKCS#1 v1.5 / SHA-256 over the raw serial number. It does not
contain JWT `exp`, a timestamp, or the client RSA public key. The JWT itself
does expire; later BLE sessions use the cached owner identity and fresh
ChaCha20-Poly1305 session parameters.

In the NotePin S firmware, command 65056 appears to clear the enrolled client
RSA public key before repeating pre-handshake. It would change pairing state;
we did not invoke it on the working Note Pro. A second PC can instead receive
the same credentials through recipient-key encrypted export.

## Firmware image boundaries

The NotePin S proprietary image is a 1,145,048-byte official OTA package. Its SHA-256 is
`03a147a3eac77e5e7899b3bbc09483f84719ff683687a620da3ccc3f087ec038`.
It contains a Realtek AmebaD host OTA image and a smaller slave image. The
host command handlers and SDK agree on the signature/RSA/ChaCha sequence.
The separately obtained Note Pro V1.7.0 package is 7,632,016 bytes, with
SHA-256 `493afef5c7efe30fefd26c686e4537493ec652001eb8174e8082cf3aceb23a2e`.
Its public key and authentication logs corroborate the shared trust root, but
source paths and log strings do not prove byte-for-byte command parity.

The detailed static notes, parser, and verification script are in the
`firmware-research` directory of the public bundle. They accept a firmware
file supplied by its owner; the proprietary image and extracted binary
segments are deliberately excluded from the source distribution.

## Practical consequences

1. **A phone is optional after bootstrap.** It is a supported SDK/API host,
   not a permanent BLE relay. A computer with BLE and the correct cached
   materials can recreate each encrypted session itself.
2. **Cloud registry and local bind are separate.** Portal Device Management
   reflects `sdk/bind`, while the device validates the SN signature and CMD 1
   identity locally. Portal visibility alone does not prove that PC BLE works;
   the two Windows reconnections are the relevant test.
3. **“Forever connected” is the wrong target.** The firmware has sleep and
   power-management behavior. Build a service that detects advertisements,
   authenticates on demand, syncs, releases BLE, and retries later.
4. **Future compatibility is conditional.** A reset, owner change, firmware
   trust-root rotation, or different authentication policy can invalidate
   cached material despite the signature having no embedded expiry.
5. **Wi-Fi can be an optimization after BLE trust.** Static analysis indicates
   BLE commands for Wi-Fi and a WebSocket handshake, but the PC bridge has not
   verified Note Pro Wi-Fi transfer. Keep reliable BLE file transfer as the
   validated baseline.

## References

- [Official low-level Android SDK reference](https://docs.plaud.ai/plaud-embedded/advanced-android-sdk)
- [Official Android SDK](https://github.com/Plaud-AI/plaud-sdk-public)
- [Independent NB-100 research, different model/protocol version](https://github.com/Kurikara-dev/plaud-note-nb100-re)
