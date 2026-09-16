from __future__ import annotations

import base64
import ctypes
import json
import os
from ctypes import wintypes
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

AAD = b"plaud-pc-bootstrap-v1"
PORTABLE_AAD = b"plaud-device-auth-portable-v1"
PRIVATE_KEY_FILE = "pc-private-key.dpapi"
PUBLIC_KEY_FILE = "pc-bootstrap-public.pem"
DEVICE_AUTH_FILE = "device-auth.dpapi"


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def _input_blob(data: bytes) -> tuple[_DataBlob, ctypes.Array[Any]]:
    buffer = ctypes.create_string_buffer(data)
    blob = _DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    return blob, buffer


def _dpapi_protect(data: bytes, description: str) -> bytes:
    if os.name != "nt":
        raise RuntimeError("Windows DPAPI is required")
    source, keepalive = _input_blob(data)
    output = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    ok = crypt32.CryptProtectData(
        ctypes.byref(source),
        description,
        None,
        None,
        None,
        0x1,  # CRYPTPROTECT_UI_FORBIDDEN
        ctypes.byref(output),
    )
    del keepalive
    if not ok:
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(output.pbData)


def _dpapi_unprotect(data: bytes) -> bytes:
    if os.name != "nt":
        raise RuntimeError("Windows DPAPI is required")
    source, keepalive = _input_blob(data)
    output = _DataBlob()
    crypt32 = ctypes.windll.crypt32
    ok = crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, 0x1, ctypes.byref(output)
    )
    del keepalive
    if not ok:
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(output.pbData)


def initialize_pc_state(state_dir: Path) -> Path:
    """Create a PC RSA key and protect its private half with the current Windows account."""
    state_dir.mkdir(parents=True, exist_ok=True)
    protected_path = state_dir / PRIVATE_KEY_FILE
    public_path = state_dir / PUBLIC_KEY_FILE

    if protected_path.exists():
        private_der = _dpapi_unprotect(protected_path.read_bytes())
        private_key = serialization.load_der_private_key(private_der, password=None)
    else:
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        private_der = private_key.private_bytes(
            serialization.Encoding.DER,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        protected_path.write_bytes(_dpapi_protect(private_der, "Plaud PC bridge RSA key"))
        private_der = b"\x00" * len(private_der)

    public_path.write_bytes(
        private_key.public_key().public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    )
    return public_path


def import_android_package(package_path: Path, state_dir: Path) -> dict[str, Any]:
    """Decrypt an Android-produced package and immediately seal it with Windows DPAPI."""
    envelope = json.loads(package_path.read_text(encoding="utf-8"))
    if envelope.get("version") != 1:
        raise ValueError("Unsupported bootstrap package version")

    private_der = _dpapi_unprotect((state_dir / PRIVATE_KEY_FILE).read_bytes())
    private_key = serialization.load_der_private_key(private_der, password=None)
    private_der = b"\x00" * len(private_der)
    aes_key = private_key.decrypt(
        base64.b64decode(envelope["wrappedKey"]),
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    plaintext = AESGCM(aes_key).decrypt(
        base64.b64decode(envelope["nonce"]),
        base64.b64decode(envelope["ciphertext"]),
        AAD,
    )
    material = json.loads(plaintext.decode("utf-8"))
    plaintext = b"\x00" * len(plaintext)
    aes_key = b"\x00" * len(aes_key)

    required = ("sn", "signature", "bindToken", "rsaPublicKey", "rsaPrivateKey")
    if material.get("version") != 1 or any(not material.get(key) for key in required):
        raise ValueError("Incomplete Plaud authentication material")
    protected = _dpapi_protect(
        json.dumps(material, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        "Plaud device authentication material",
    )
    (state_dir / DEVICE_AUTH_FILE).write_bytes(protected)
    return {"version": 1, "serialSuffix": str(material["sn"])[-4:]}


def load_device_material(state_dir: Path) -> dict[str, Any]:
    """Load authentication material into memory without printing or writing plaintext."""
    plaintext = _dpapi_unprotect((state_dir / DEVICE_AUTH_FILE).read_bytes())
    try:
        return json.loads(plaintext.decode("utf-8"))
    finally:
        plaintext = b"\x00" * len(plaintext)


def save_device_material(state_dir: Path, material: dict[str, Any]) -> dict[str, Any]:
    """Validate and seal a freshly provisioned device identity with Windows DPAPI."""
    required = ("sn", "signature", "bindToken", "rsaPublicKey", "rsaPrivateKey")
    if material.get("version") != 1 or any(not material.get(key) for key in required):
        raise ValueError("Incomplete Plaud authentication material")
    state_dir.mkdir(parents=True, exist_ok=True)
    plaintext = json.dumps(
        material, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    try:
        protected = _dpapi_protect(plaintext, "Plaud device authentication material")
        destination = state_dir / DEVICE_AUTH_FILE
        temporary = destination.with_suffix(destination.suffix + ".part")
        temporary.write_bytes(protected)
        temporary.replace(destination)
    finally:
        plaintext = b"\x00" * len(plaintext)
    return {"version": 1, "serialSuffix": str(material["sn"])[-4:]}


def export_device_package_for_recipient(
    state_dir: Path,
    recipient_public_key_path: Path,
    output_path: Path,
) -> dict[str, Any]:
    """Re-encrypt the complete device identity to a recipient-controlled RSA public key."""
    recipient = serialization.load_pem_public_key(recipient_public_key_path.read_bytes())
    if not isinstance(recipient, rsa.RSAPublicKey) or recipient.key_size < 3072:
        raise ValueError("recipient key must be an RSA public key of at least 3072 bits")
    material = load_device_material(state_dir)
    plaintext = json.dumps(
        material, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    aes_key = AESGCM.generate_key(bit_length=256)
    nonce = os.urandom(12)
    ciphertext = AESGCM(aes_key).encrypt(nonce, plaintext, AAD)
    wrapped_key = recipient.encrypt(
        aes_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    envelope = {
        "version": 1,
        "purpose": "plaud-device-auth-migration",
        "keyWrap": "RSA-OAEP-SHA256",
        "contentEncryption": "AES-256-GCM",
        "aad": AAD.decode("ascii"),
        "wrappedKey": base64.b64encode(wrapped_key).decode("ascii"),
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".part")
    temporary.write_text(
        json.dumps(envelope, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    temporary.replace(output_path)
    plaintext = b"\x00" * len(plaintext)
    aes_key = b"\x00" * len(aes_key)
    return {"version": 1, "serialSuffix": str(material["sn"])[-4:]}


def create_portable_envelope(material: dict[str, Any], password: str) -> dict[str, Any]:
    """Encrypt device identity with a user-transferable passphrase."""
    if len(password) < 20:
        raise ValueError("portable export password must be at least 20 characters")
    salt = os.urandom(16)
    nonce = os.urandom(12)
    key = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(
        password.encode("utf-8")
    )
    plaintext = json.dumps(
        material, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, PORTABLE_AAD)
    envelope = {
        "version": 1,
        "purpose": "plaud-device-auth-portable",
        "contentEncryption": "AES-256-GCM",
        "kdf": "scrypt",
        "scrypt": {"n": 2**15, "r": 8, "p": 1, "salt": base64.b64encode(salt).decode("ascii")},
        "aad": PORTABLE_AAD.decode("ascii"),
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
    }
    plaintext = b"\x00" * len(plaintext)
    key = b"\x00" * len(key)
    return envelope


def decrypt_portable_envelope(envelope: dict[str, Any], password: str) -> dict[str, Any]:
    if envelope.get("version") != 1 or envelope.get("purpose") != "plaud-device-auth-portable":
        raise ValueError("unsupported portable Plaud authentication package")
    params = envelope.get("scrypt") or {}
    if (params.get("n"), params.get("r"), params.get("p")) != (2**15, 8, 1):
        raise ValueError("unsupported portable package KDF parameters")
    key = Scrypt(
        salt=base64.b64decode(params["salt"]), length=32, n=2**15, r=8, p=1
    ).derive(password.encode("utf-8"))
    plaintext = AESGCM(key).decrypt(
        base64.b64decode(envelope["nonce"]),
        base64.b64decode(envelope["ciphertext"]),
        PORTABLE_AAD,
    )
    try:
        material = json.loads(plaintext.decode("utf-8"))
        required = ("sn", "signature", "bindToken", "rsaPublicKey", "rsaPrivateKey")
        if material.get("version") != 1 or any(not material.get(name) for name in required):
            raise ValueError("portable package contains incomplete authentication material")
        return material
    finally:
        plaintext = b"\x00" * len(plaintext)
        key = b"\x00" * len(key)


def load_portable_package(package_path: Path, password: str) -> dict[str, Any]:
    return decrypt_portable_envelope(
        json.loads(package_path.read_text(encoding="utf-8")), password
    )


def create_portable_secret_envelope(name: str, value: str, password: str) -> dict[str, Any]:
    if len(password) < 20:
        raise ValueError("portable export password must be at least 20 characters")
    if not name or not value:
        raise ValueError("portable secret name and value are required")
    salt = os.urandom(16)
    nonce = os.urandom(12)
    key = Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(password.encode("utf-8"))
    payload = json.dumps({"name": name, "value": value}, separators=(",", ":")).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(nonce, payload, PORTABLE_AAD)
    result = {
        "version": 1,
        "purpose": "plaud-handoff-secret",
        "contentEncryption": "AES-256-GCM",
        "kdf": "scrypt",
        "scrypt": {"n": 2**15, "r": 8, "p": 1, "salt": base64.b64encode(salt).decode("ascii")},
        "aad": PORTABLE_AAD.decode("ascii"),
        "nonce": base64.b64encode(nonce).decode("ascii"),
        "ciphertext": base64.b64encode(ciphertext).decode("ascii"),
    }
    payload = b"\x00" * len(payload)
    key = b"\x00" * len(key)
    return result


def decrypt_portable_secret_envelope(envelope: dict[str, Any], password: str) -> dict[str, str]:
    if envelope.get("version") != 1 or envelope.get("purpose") != "plaud-handoff-secret":
        raise ValueError("unsupported portable secret package")
    params = envelope.get("scrypt") or {}
    if (params.get("n"), params.get("r"), params.get("p")) != (2**15, 8, 1):
        raise ValueError("unsupported portable secret KDF parameters")
    key = Scrypt(
        salt=base64.b64decode(params["salt"]), length=32, n=2**15, r=8, p=1
    ).derive(password.encode("utf-8"))
    payload = AESGCM(key).decrypt(
        base64.b64decode(envelope["nonce"]),
        base64.b64decode(envelope["ciphertext"]),
        PORTABLE_AAD,
    )
    try:
        result = json.loads(payload.decode("utf-8"))
        if not result.get("name") or not result.get("value"):
            raise ValueError("portable secret package is incomplete")
        return {"name": str(result["name"]), "value": str(result["value"])}
    finally:
        payload = b"\x00" * len(payload)
        key = b"\x00" * len(key)
