# RE Progress — 2026-10-21 (NotePin S)

Status update for the firmware reverse-engineering effort. Companion to
[USB-WIFI-RE-20261020.md](USB-WIFI-RE-20261020.md).

## New artifacts

| File | Content |
|---|---|
| `analysis-function-map.json` | Full KM4 function map: 2917 functions (XIP 2316 + SRAM 601) with string annotations, call targets, immediate comparisons |
| `analysis-function-index.txt` | Searchable text index of the same |
| `build_function_map.py` | Generator (prologue scan + Capstone annotation) |

## New protocol findings

### BLE command dispatch table (main, 34 entries)

Located at container offset `0xBD04C` — pairs of (command id, handler) covering
ids 2-138. Notable mappings: cmd 3 pen state, 6 capacity, 9 battery, 10 openWiFi,
26 file list, 28-31 download family, 103-109 settings setters, 128 FindMy.

### Second (indexed) WiFi command table

Container offset `0xD60C8`: cmd 14 (WiFi data entry), 15, 18, 21 — handlers at
`0xE063CBx-D2x` operating on the SoftAP/WebSocket session context.

### Command 121 — SetWifiReq (WiFi configuration family)

Confirmed from the vendor SDK (RecorderHub build, androguard decompile):

```
payload = operation u8
        + wifiIndex u32 LE
        + ssid_len u8 + ssid[32] (zero padded, <=32 bytes)
        + pwd_len  u8 + pwd[32]
```

Family: 121 SetWifi, plus GetWifiList / GetWifiInfo / DeleteWifi (125) /
TestWifiInfo / GetWifiSyncStart. cmd 125 deleteWifiInfo confirmed from SDK
(`sendData(new int[] {125}, new DeleteWifiReq(list).enPkg())`).

### Command 102 — RestoreFactorySettings

`RestoreFactorySettingsReq` (bleRequestType 102, no payload). Firmware handler
at `0x0E00FD28`. Companion strings: `mmi_ble_handle_reset_bt_device_ind:
clear_file=%d`, `mmi_ble_handle_findmy_factroy_reset`.

### Command 146 — DeleteDeviceLogFile

`DeleteDeviceLogFileReq(a, b)` → `sendData(new int[] {146}, ...)`.

### LED state machine

States observed: FOTA, SECRETLY, WIFI ON, WIFI SEARCHING, wifi_paring, PARING,
CHARGING, RECORDING, DISK FULL, POWER WARNING, idle_sync, rec mark. A 12-second
long press enters the PARING state (green blink) without clearing the client
key slot (verified by 65040 probe: slot still occupied after PARING entry).

## USB wired channel — final verdict

The production image exposes a CDC-ACM virtual serial port only
(VID_0E8D PID_0023, Class_02/SubClass_02). The port is silent at runtime,
during boot, and across re-enumeration (0 bytes captured at all stages).
The MSC (usbms) mode exists in the image behind `query(17)==1`, but the query
implementation resides in SRAM outside the OTA-covered region and no caller
was identified statically. Conclusion: no practical wired data path without
further (Ghidra-class) analysis; BLE and the WiFi fast-transfer route cover
the use cases.

## Device key architecture (verified by experiment)

- Device stores exactly ONE client RSA public key (single slot); last
  registrant wins.
- Session keys (ChaCha20-Poly1305 key/nonce/AAD) are generated per connection
  by the device and wrapped with the stored client public key.
- The SN signature (RSA-2048 over the serial number, issued by PLAUD, no
  expiry) is the ownership credential: 65056 force-clear requires it, works
  even while the slot is occupied, and is not bound to any specific client
  key. Consequences: possession of SN + signature = ability to take over the
  device; keep them secret.
- Today the slot was cycled PC key -> intl app key -> empty -> PC key several
  times; every transition behaved as described.

## Toolchain ceiling

Static analysis with free tools has reached its limit: `query(17)` (the MSC
gate) points into SRAM outside the OTA-covered region, and the log-string
reference mechanism requires whole-program analysis (Ghidra) to traverse
indirect calls. Everything else of practical value (BLE control plane, WiFi
fast-transfer wire format, config commands) has been extracted.
