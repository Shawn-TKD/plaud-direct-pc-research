from __future__ import annotations

import hashlib
import json
import secrets
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from .bootstrap import (
    create_portable_envelope,
    create_portable_secret_envelope,
    load_device_material,
)


MAC_README = """# Plaud NotePin Mac Agent handoff

This bundle contains the owner-authorized direct BLE client and one encrypted device identity.
It contains encrypted device identity, encrypted SiliconFlow API credentials, and the owner's
downloaded recording artifacts.

## Security

- Ask the owner for the transfer password separately. It was placed on the Windows clipboard.
- Never print or commit the decrypted material.
- `secrets/plaud-device-auth.portable.json` contains the complete BLE identity encrypted with
  scrypt + AES-256-GCM.
- `secrets/siliconflow-api-key.portable.json` contains the SiliconFlow key under the same
  passphrase and must never be printed or committed.
- The required fields after in-memory decryption are `sn`, `signature`, `bindToken`,
  `rsaPublicKey`, and `rsaPrivateKey`.

## Setup on macOS

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e .
python -m unittest discover -s tests -v
```

## Agent integration

Prompt for the password with `getpass.getpass()`, then call:

```python
from pathlib import Path
from plaud_pc.bootstrap import load_portable_package
from plaud_pc.client import PlaudBleClient

material = load_portable_package(
    Path("secrets/plaud-device-auth.portable.json"), password
)
# Never log `material`.
```

To load the SiliconFlow key in memory, read its JSON and call
`decrypt_portable_secret_envelope(envelope, password)`. Put the returned value in the process
environment only for the ASR request; do not write it to a plaintext file.

Use `PlaudBleClient(material)` for scanning, authentication, file listing and downloads.
Only one phone/computer should hold the NotePin BLE connection at a time. The protocol path is
offline; cloud ASR remains optional and requires network access.

Known validated flow: cached SN signature; paired RSA key; PKCS#1 v1.5 session unwrap;
`PLAUD.AI` challenge; ChaCha20-Poly1305; CMD 1 bind token; CMD 9 battery; CMD 6 storage;
CMD 26 file list; CMD 28 download; 512-byte audio header decrypt; 80-byte Opus frame wrapping.

Do not implement or invoke device deletion until the owner explicitly asks for it.
"""


def _put_password_on_windows_clipboard(password: str) -> None:
    subprocess.run(
        ["powershell.exe", "-NoProfile", "-Command", "Set-Clipboard"],
        input=password,
        text=True,
        check=True,
        capture_output=True,
    )


def build_mac_handoff(
    project_dir: Path,
    state_dir: Path,
    export_root: Path,
    recordings_dir: Path,
    siliconflow_key_file: Path,
    password_output: Path | None = None,
) -> tuple[Path, str]:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    name = f"plaud-notepin-mac-handoff-{stamp}"
    folder = export_root / name
    folder.mkdir(parents=True, exist_ok=False)
    password = secrets.token_urlsafe(32)
    material = load_device_material(state_dir)

    shutil.copytree(
        project_dir / "src", folder / "src", ignore=shutil.ignore_patterns("__pycache__", "*.pyc")
    )
    shutil.copytree(
        project_dir / "tests", folder / "tests", ignore=shutil.ignore_patterns("__pycache__", "*.pyc")
    )
    shutil.copy2(project_dir / "pyproject.toml", folder / "pyproject.toml")
    shutil.copy2(project_dir / "README.md", folder / "README.md")
    (folder / "README_MAC_AGENT.md").write_text(MAC_README, encoding="utf-8")
    secret_dir = folder / "secrets"
    secret_dir.mkdir()
    auth_path = secret_dir / "plaud-device-auth.portable.json"
    auth_path.write_text(
        json.dumps(create_portable_envelope(material, password), indent=2), encoding="utf-8"
    )
    siliconflow_key = siliconflow_key_file.read_text(encoding="utf-8").strip()
    if not siliconflow_key:
        raise ValueError("SiliconFlow API key file is empty")
    (secret_dir / "siliconflow-api-key.portable.json").write_text(
        json.dumps(
            create_portable_secret_envelope("SILICONFLOW_API_KEY", siliconflow_key, password),
            indent=2,
        ),
        encoding="utf-8",
    )
    shutil.copytree(
        recordings_dir,
        folder / "recordings",
        ignore=shutil.ignore_patterns("*.part", "__pycache__", "*.pyc"),
    )

    manifest: dict[str, str] = {}
    for path in sorted(candidate for candidate in folder.rglob("*") if candidate.is_file()):
        manifest[path.relative_to(folder).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    (folder / "MANIFEST.sha256.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    archive = export_root / f"{name}.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in sorted(candidate for candidate in folder.rglob("*") if candidate.is_file()):
            output.write(path, Path(name) / path.relative_to(folder))
    if password_output is not None:
        password_output.parent.mkdir(parents=True, exist_ok=True)
        password_output.write_text(password, encoding="utf-8")
    else:
        _put_password_on_windows_clipboard(password)
    return archive, str(material["sn"])[-4:]
