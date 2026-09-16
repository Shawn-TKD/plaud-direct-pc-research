# PLAUD direct-PC bridge

An experimental, owner-operated Windows BLE client for PLAUD Note Pro and NotePin S.
It implements the device's RSA and ChaCha20-Poly1305 handshake, reads battery and
storage status, lists recordings, and can download and convert recordings without
keeping a phone connected. It does not bypass device authentication.

The first setup still needs a PLAUD-issued signature for the device serial number,
an RSA key pair, and the binding identity derived from a User Access Token. The
short-lived JWT is used to request those materials from PLAUD; it is not sent to
the device during later offline BLE reconnections. A successful first connection
does not guarantee that a future firmware or account change will preserve pairing.

See [the complete Note Pro case study](docs/NOTE-PRO-BOOTSTRAP.md) and
[the protocol and firmware findings](docs/PROTOCOL-AND-FIRMWARE.md).

## Two first-setup routes

**Direct PC API route.** With partner API access permitted for your computer,
copy `partner-bootstrap.example.json` to the git-ignored `partner-bootstrap.json`
and fill in your own Client ID, Client Secret, stable User ID, and device serial:

```powershell
python -m pip install -e .
plaud-pc provision-notepro --config partner-bootstrap.json --state-dir .local-state-notepro
plaud-pc connect-readonly --state-dir .local-state-notepro
```

This obtains Partner Token -> User JWT -> `gen-key` -> `sn-sign`, then immediately
protects the lasting material with Windows DPAPI. On the machine used in our
September 2026 case study, PLAUD's edge returned HTTP 403 / code 1010 for this
Python client. That is an API access restriction, not a BLE requirement. Ask
PLAUD for a supported desktop API path rather than attempting to evade the edge.

If you already have a current User JWT, you can omit the Client Secret config:

```powershell
plaud-pc provision-notepro --user-token-stdin --sn 881YOUR_SERIAL --state-dir .local-state-notepro
```

The terminal prompts for the token without echoing it. The client and stable
user IDs are read from its claims; PLAUD still validates the JWT on the API.

**Official Android SDK bootstrap.** The companion Android source offers a
one-time online bootstrap. Enter the User JWT at runtime, then either connect
the phone to the device to verify pairing or use the experimental “prepare PC
credentials” route, which calls `gen-key` and `sn-sign` using a known serial
without opening BLE on the phone. The latter still needs real-PC connection
validation and may require cloud registration separately.

Generate a PC recipient key before exporting:

```powershell
plaud-pc bootstrap-init --state-dir .local-state-notepro
adb push .local-state-notepro/pc-bootstrap-public.pem /data/local/tmp/pc-bootstrap-public.pem
adb shell run-as com.shawn.recorderhub.noteprobootstrap cp /data/local/tmp/pc-bootstrap-public.pem files/pc-bootstrap-public.pem
```

Then open the Android bootstrap app and complete either route. The app encrypts
the export to that PC public key. If the app was already provisioned, restart it
once after placing the public key. Retrieve only the encrypted envelope:

```powershell
adb exec-out run-as com.shawn.recorderhub.noteprobootstrap cat files/pc-bootstrap-v1.json > .local-state-notepro/pc-bootstrap-v1.json
plaud-pc bootstrap-import --state-dir .local-state-notepro --package .local-state-notepro/pc-bootstrap-v1.json
adb shell am force-stop com.shawn.recorderhub.noteprobootstrap
plaud-pc connect-readonly --state-dir .local-state-notepro --scan-seconds 60
```

The Android variant is a debug build, so `run-as` works over an authorized USB
debugging connection. The PLAUD SDK AAR comes from the official
[PLAUD public SDK repository](https://github.com/Plaud-AI/plaud-sdk-public); the
Android build does not place Client Secret in the APK.

## Moving the same device identity to another PC

The encrypted `device-auth.dpapi` cannot simply be copied to another Windows
account. Generate a recipient public key on the second PC with `bootstrap-init`,
then use `bootstrap-export-for-recipient` on the current PC and `bootstrap-import`
on the recipient. The material remains encrypted in transit. Only one BLE client
can occupy the device at a time.

## Firmware research and scope

The available local firmware image is **NotePin S V1.2.7**, not Note Pro V1.7.0.
Its embedded PLAUD public key successfully verified the newly obtained Note Pro
SN signature. This establishes a shared signature verification key for those
observations, and the signed message was the raw serial number. The Note Pro
firmware itself has not been inspected, so device-specific implementation claims
remain provisional. Firmware binaries, extracted proprietary code, credential
files, recordings, and the vendor SDK AAR are not part of the public source tree.

## Safety defaults

- Local credentials are stored under the current Windows user's DPAPI key.
- Recordings stay on the device after download. Deletion is a separate action.
- `CMD 29` stops a transfer; `CMD 30` deletes a recording. The bridge does not
  conflate them.
- Never put Client Secret, JWT, SN signature, RSA private key, or encrypted
  identity bundles in a public repository. An encrypted bundle remains a
  sensitive owner credential even if its contents cannot be read immediately.
