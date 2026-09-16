from __future__ import annotations

import argparse
import base64
import sys
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Test likely signed messages without exposing cached PLAUD credentials"
    )
    parser.add_argument("bridge", type=Path)
    parser.add_argument("state", type=Path)
    parser.add_argument("public_key", type=Path)
    args = parser.parse_args()

    sys.path.insert(0, str(args.bridge / "src"))
    from plaud_pc.bootstrap import load_device_material

    material = load_device_material(args.state)
    signature = base64.b64decode(str(material["signature"]), validate=True)
    serial = str(material["sn"])
    public_key = serialization.load_pem_public_key(args.public_key.read_bytes())

    device_types = ["882", "notepins", "notepin_s", "note_pin_s", "NOTE_PIN_S"]
    candidates: dict[str, bytes] = {"sn": serial.encode("utf-8")}
    for device_type in device_types:
        candidates[f"{device_type}+sn"] = f"{device_type}{serial}".encode("utf-8")
        candidates[f"{device_type}:sn"] = f"{device_type}:{serial}".encode("utf-8")
        candidates[f"json-{device_type}"] = (
            '{"type":"' + device_type + '","sn":"' + serial + '"}'
        ).encode("utf-8")

    verified: list[str] = []
    for label, message in candidates.items():
        try:
            public_key.verify(signature, message, padding.PKCS1v15(), hashes.SHA256())
        except InvalidSignature:
            continue
        verified.append(label)

    print(f"signature_bytes={len(signature)}")
    print(f"public_key_bits={public_key.key_size}")
    print("verified_candidates=" + (",".join(verified) if verified else "none"))


if __name__ == "__main__":
    main()
