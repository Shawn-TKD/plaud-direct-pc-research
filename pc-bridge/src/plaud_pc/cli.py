from __future__ import annotations

import argparse
import asyncio
import getpass
import importlib.util
import platform
import json
import sys
from pathlib import Path

SERVICE_UUID = "00001910-0000-1000-8000-00805f9b34fb"


def diagnose() -> int:
    print(f"OS: {platform.platform()}")
    print(f"bleak installed: {importlib.util.find_spec('bleak') is not None}")
    if platform.system() == "Windows":
        print("Windows BLE requires a Bluetooth adapter enumerated by Device Manager.")
    return 0


async def scan(seconds: float) -> int:
    try:
        from bleak import BleakScanner
    except ImportError:
        print("bleak is not installed; run: python -m pip install -e .")
        return 2

    devices = await BleakScanner.discover(timeout=seconds, return_adv=True)
    matches = []
    from .advertisement import parse_manufacturer_data

    for address, (device, advertisement) in devices.items():
        uuids = {value.lower() for value in advertisement.service_uuids}
        name = device.name or advertisement.local_name or ""
        if SERVICE_UUID in uuids or "plaud" in name.lower():
            parsed = parse_manufacturer_data(advertisement.manufacturer_data)
            matches.append((address, name, advertisement.rssi, parsed))
    if not matches:
        print("No Plaud advertisement found.")
        return 1
    for address, name, rssi, parsed in sorted(matches, key=lambda row: row[2], reverse=True):
        details = ""
        if parsed is not None:
            details = (
                f"  SN={parsed.serial_number} version={parsed.version_name}"
                f" port={parsed.port_version}"
            )
        elif advertisement.manufacturer_data:
            raw = ",".join(
                f"{company_id:04X}:{payload.hex()}"
                for company_id, payload in advertisement.manufacturer_data.items()
            )
            details = f"  manufacturer={raw}"
        print(f"{name or 'Plaud device'}  {address}  RSSI={rssi}{details}")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(prog="plaud-pc")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("diagnose")
    scan_parser = sub.add_parser("scan")
    scan_parser.add_argument("--seconds", type=float, default=15.0)
    provision_parser = sub.add_parser("provision-notepro")
    provision_parser.add_argument("--config", type=Path)
    provision_parser.add_argument("--state-dir", type=Path, required=True)
    provision_parser.add_argument("--sn", help="Note Pro SN; defaults to serialNumber in config")
    provision_parser.add_argument(
        "--user-token-stdin", action="store_true",
        help="read an existing User Access Token from standard input instead of requesting one",
    )
    init_parser = sub.add_parser("bootstrap-init")
    init_parser.add_argument("--state-dir", type=Path, required=True)
    import_parser = sub.add_parser("bootstrap-import")
    import_parser.add_argument("--state-dir", type=Path, required=True)
    import_parser.add_argument("--package", type=Path, required=True)
    export_parser = sub.add_parser("bootstrap-export-for-recipient")
    export_parser.add_argument("--state-dir", type=Path, required=True)
    export_parser.add_argument("--recipient-public-key", type=Path, required=True)
    export_parser.add_argument("--output", type=Path, required=True)
    connect_parser = sub.add_parser("connect-readonly")
    connect_parser.add_argument("--state-dir", type=Path, required=True)
    connect_parser.add_argument("--scan-seconds", type=float, default=60.0)
    download_parser = sub.add_parser("download-latest")
    download_parser.add_argument("--state-dir", type=Path, required=True)
    download_parser.add_argument("--output-dir", type=Path, default=Path("recordings"))
    download_parser.add_argument("--scan-seconds", type=float, default=60.0)
    decrypt_parser = sub.add_parser("decrypt-audio")
    decrypt_parser.add_argument("--state-dir", type=Path, required=True)
    decrypt_parser.add_argument("--input", type=Path, required=True)
    decrypt_parser.add_argument("--output", type=Path, required=True)
    wrap_parser = sub.add_parser("wrap-opus")
    wrap_parser.add_argument("--input", type=Path, required=True)
    wrap_parser.add_argument("--output", type=Path, required=True)
    wrap_parser.add_argument("--channels", type=int, default=1)
    wrap_parser.add_argument("--frame-size", type=int, default=80)
    handoff_parser = sub.add_parser("build-mac-handoff")
    handoff_parser.add_argument("--state-dir", type=Path, required=True)
    handoff_parser.add_argument("--output-dir", type=Path, required=True)
    handoff_parser.add_argument("--recordings-dir", type=Path, required=True)
    handoff_parser.add_argument("--siliconflow-key-file", type=Path, required=True)
    handoff_parser.add_argument("--password-output", type=Path)
    args = parser.parse_args()
    if args.command == "diagnose":
        raise SystemExit(diagnose())
    if args.command == "scan":
        raise SystemExit(asyncio.run(scan(args.seconds)))
    if args.command == "provision-notepro":
        from .cloud_bootstrap import provision_note_pro

        try:
            if args.config is None and not args.user_token_stdin:
                raise ValueError("provide --config or --user-token-stdin")
            user_token = (
                (getpass.getpass("User Access Token: ") if sys.stdin.isatty() else sys.stdin.readline()).strip()
                if args.user_token_stdin
                else None
            )
            if args.user_token_stdin and not user_token:
                raise ValueError("No User Access Token was provided on standard input")
            result = provision_note_pro(args.config, args.state_dir, args.sn, user_token)
        except (RuntimeError, ValueError, KeyError) as error:
            print(f"Provisioning stopped: {error}", file=sys.stderr)
            raise SystemExit(1) from None
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    if args.command == "connect-readonly":
        from .bootstrap import load_device_material
        from .client import PlaudBleClient

        material = load_device_material(args.state_dir)
        snapshot = asyncio.run(PlaudBleClient(material).connect_read_only(args.scan_seconds))
        gib = 1024**3
        print(f"Connected: {snapshot.name}")
        print(f"Battery: {snapshot.battery_percent}% (charging={snapshot.charging})")
        print(f"Storage: {snapshot.free_bytes / gib:.2f} / {snapshot.total_bytes / gib:.2f} GiB free")
        print(f"Files: {len(snapshot.files)}")
        for item in snapshot.files:
            print(
                "  "
                f"session_id={item.session_id} "
                f"size={item.size} "
                f"scene={item.scene} "
                f"marked={item.marked}"
            )
        raise SystemExit(0)
    if args.command == "download-latest":
        from .bootstrap import load_device_material
        from .client import PlaudBleClient

        material = load_device_material(args.state_dir)
        snapshot, result = asyncio.run(
            PlaudBleClient(material).connect_download_latest(
                args.output_dir, args.scan_seconds
            )
        )
        print(f"Connected: {snapshot.name}")
        print(f"Files: {len(snapshot.files)}")
        print(
            f"Downloaded: session_id={result.file.session_id} "
            f"size={result.file.size} scene={result.file.scene} "
            f"marked={result.file.marked} crc={result.crc}"
        )
        print(f"Saved: {result.path.resolve()}")
        print("Device recording retained: yes")
        raise SystemExit(0)
    if args.command == "decrypt-audio":
        from .audio import decrypt_plaud_audio
        from .bootstrap import load_device_material

        material = load_device_material(args.state_dir)
        header = decrypt_plaud_audio(
            args.input, args.output, str(material["rsaPrivateKey"])
        )
        print(
            f"Decrypted: file_type={header.file_type} channels={header.channels} "
            f"segment={header.segment} duration={header.duration}"
        )
        print(f"Saved: {args.output.resolve()}")
        raise SystemExit(0)
    if args.command == "wrap-opus":
        from .audio import wrap_raw_opus

        frames = wrap_raw_opus(
            args.input,
            args.output,
            channels=args.channels,
            frame_size=args.frame_size,
        )
        print(f"Wrapped: frames={frames} duration_seconds={frames * 0.02:.2f}")
        print(f"Saved: {args.output.resolve()}")
        raise SystemExit(0)
    if args.command == "bootstrap-export-for-recipient":
        from .bootstrap import export_device_package_for_recipient

        result = export_device_package_for_recipient(
            args.state_dir, args.recipient_public_key, args.output
        )
        print(
            "Encrypted migration package ready "
            f"(serial suffix {result['serialSuffix']}): {args.output.resolve()}"
        )
        print("Plaintext credentials written: no")
        raise SystemExit(0)
    if args.command == "build-mac-handoff":
        from .handoff import build_mac_handoff

        archive, suffix = build_mac_handoff(
            Path(__file__).resolve().parents[2],
            args.state_dir,
            args.output_dir,
            args.recordings_dir,
            args.siliconflow_key_file,
            args.password_output,
        )
        print(f"Mac handoff ZIP ready (serial suffix {suffix}): {archive.resolve()}")
        if args.password_output:
            print(f"Transfer password file: {args.password_output.resolve()}")
        else:
            print("Transfer password copied to Windows clipboard: yes")
        print("Plaintext credentials written: no")
        raise SystemExit(0)
    from .bootstrap import import_android_package, initialize_pc_state

    if args.command == "bootstrap-init":
        path = initialize_pc_state(args.state_dir)
        print(f"PC bootstrap public key ready: {path}")
        raise SystemExit(0)
    result = import_android_package(args.package, args.state_dir)
    print(f"Encrypted device credentials imported (serial suffix {result['serialSuffix']}).")


if __name__ == "__main__":
    main()
