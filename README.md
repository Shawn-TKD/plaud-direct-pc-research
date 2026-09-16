# PLAUD direct-PC research

Owner-controlled research and source code for using a PLAUD Note Pro or
NotePin S with a Windows computer over BLE. A one-time PLAUD-issued device
signature and RSA identity are required; this project does not remove or
bypass device authentication. Once an owner has provisioned that identity,
the PC can establish fresh encrypted BLE sessions without a phone relay or
a live User JWT.

The repository has three parts:

- [`pc-bridge/`](pc-bridge/) — Python Windows BLE client, encrypted credential
  import/export, status and recording transfer.
- [`android/`](android/) — source for the one-time official SDK bootstrap app.
  Obtain `plaud-sdk.aar` from the [official SDK repository](https://github.com/Plaud-AI/plaud-sdk-public)
  and place it in `android/app/libs/` before building; it is not redistributed
  here. The app also includes other recorder integrations inherited from the
  working Recorder Hub project.
- [`firmware-research/`](firmware-research/) — offline analysis of owner-obtained
  NotePin S V1.2.7 and Note Pro V1.7.0 firmware, including a
  [model comparison](firmware-research/NOTE-PRO-VS-NOTEPIN-S.md). No vendor
  firmware, extracted binary, or decompiled vendor source is published.

Start with the [step-by-step Note Pro bootstrap case study](pc-bridge/docs/NOTE-PRO-BOOTSTRAP.md),
then read the [protocol and firmware evidence](pc-bridge/docs/PROTOCOL-AND-FIRMWARE.md).
For a Chinese narrative of the research and the Android app's SiliconFlow and
IdeaShell MCP integrations, read [the owner-app case study](docs/PLAUD-OWN-APP-RESEARCH-ZH.md).
The experimentally tested Android-first route completed a Note Pro BLE bind,
encrypted credential migration, and two fresh direct Windows connections on
2026-09-16. The faster “prepare PC credentials without phone BLE” route builds
but has not yet been tested on a new device. A direct Windows call to the PLAUD
SDK API was denied at the service edge on this machine; use a PLAUD-supported
API path if desktop-only first provisioning is required.

This project is an independent interoperability study. PLAUD and product names
belong to their respective owners. See [`NOTICE`](NOTICE) for source attribution
and [`LICENSE`](LICENSE) for the source-code license.
