from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from .bootstrap import save_device_material


DEFAULT_BASE_URL = "https://platform-us.plaud.ai/developer/api"


@dataclass(frozen=True)
class PartnerCredentials:
    client_id: str
    client_secret: str
    user_id: str
    serial_number: str = ""
    base_url: str = DEFAULT_BASE_URL


def _post(
    url: str,
    *,
    headers: dict[str, str],
    body: bytes = b"",
) -> dict[str, Any]:
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        if error.code == 403 and "error code: 1010" in detail.lower():
            raise RuntimeError(
                "PLAUD rejected this API client at its edge (HTTP 403, code 1010); "
                "no device credentials were created"
            ) from error
        raise RuntimeError(f"PLAUD API returned HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Cannot reach PLAUD API: {error.reason}") from error


def _find_string(value: Any, names: tuple[str, ...]) -> str:
    if isinstance(value, dict):
        for name in names:
            candidate = value.get(name)
            if isinstance(candidate, str) and candidate.strip():
                return candidate.strip()
        for candidate in value.values():
            try:
                return _find_string(candidate, names)
            except KeyError:
                pass
    raise KeyError(f"Response did not contain any of: {', '.join(names)}")


def load_credentials(path: Path, *, user_token_supplied: bool = False) -> PartnerCredentials:
    raw = json.loads(path.read_text(encoding="utf-8"))
    credentials = PartnerCredentials(
        client_id=str(raw.get("clientId", "")).strip(),
        client_secret=str(raw.get("clientSecret", "")).strip(),
        user_id=str(raw.get("userId", "")).strip(),
        serial_number=str(raw.get("serialNumber", "")).strip(),
        base_url=str(raw.get("baseUrl", DEFAULT_BASE_URL)).rstrip("/"),
    )
    if not all((credentials.client_id, credentials.user_id)) or (
        not user_token_supplied and not credentials.client_secret
    ):
        raise ValueError("partner config requires clientId and userId; clientSecret is required without a user token")
    if not credentials.base_url.startswith("https://"):
        raise ValueError("partner baseUrl must use HTTPS")
    return credentials


def _decode_jwt_payload(token: str) -> dict[str, Any]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("PLAUD user access token is not a JWT")
    padded = parts[1] + "=" * (-len(parts[1]) % 4)
    return json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))


def _normalize_bind_token(subject: str) -> str:
    value = subject.removeprefix("client_user_").replace("-", "")
    if not re.fullmatch(r"[A-Za-z0-9]{32}", value):
        raise ValueError("JWT subject does not normalize to the 32-byte BLE bind token")
    return value


def provision_note_pro(
    config_path: Path | None,
    state_dir: Path,
    serial_number: str | None = None,
    user_token: str | None = None,
) -> dict[str, Any]:
    if config_path is None:
        if user_token is None:
            raise ValueError("a config or User Access Token is required")
        token_claims = _decode_jwt_payload(user_token)
        credentials = PartnerCredentials(
            client_id=str(token_claims.get("client_id", "")),
            client_secret="",
            user_id=str(token_claims.get("user_id", "")),
        )
        if not credentials.client_id or not credentials.user_id:
            raise ValueError("User token is missing client_id or user_id")
    else:
        credentials = load_credentials(config_path, user_token_supplied=user_token is not None)
    serial = (serial_number or credentials.serial_number).strip()
    if not re.fullmatch(r"881[A-Za-z0-9_\-]+", serial):
        raise ValueError("Note Pro serial number must start with 881")

    if user_token is None:
        basic = base64.b64encode(
            f"{credentials.client_id}:{credentials.client_secret}".encode("utf-8")
        ).decode("ascii")
        partner_response = _post(
            f"{credentials.base_url}/oauth/partner/access-token",
            headers={
                "Authorization": f"Basic {basic}",
                "Content-Type": "application/x-www-form-urlencoded",
            },
        )
        partner_token = _find_string(
            partner_response, ("access_token", "user_access_token", "token")
        )
        user_response = _post(
            f"{credentials.base_url}/open/partner/users/access-token",
            headers={
                "Authorization": f"Bearer {partner_token}",
                "Content-Type": "application/json",
            },
            body=json.dumps(
                {"user_id": credentials.user_id, "expires_in": 86400},
                separators=(",", ":"),
            ).encode("utf-8"),
        )
        user_token = _find_string(
            user_response, ("access_token", "user_access_token", "token")
        )
    claims = _decode_jwt_payload(user_token)
    if claims.get("client_id") != credentials.client_id or claims.get("user_id") != credentials.user_id:
        raise ValueError("User token does not belong to the configured clientId and userId")
    if int(claims.get("exp", 0)) <= datetime.now(timezone.utc).timestamp():
        raise ValueError("User token has expired")
    bind_token = _normalize_bind_token(str(claims.get("sub", "")))

    bearer_headers = {
        "Authorization": f"Bearer {user_token}",
        "Content-Type": "application/json",
    }
    key_response = _post(
        f"{credentials.base_url}/open/partner/sdk/gen-key",
        headers=bearer_headers,
    )
    public_key = _find_string(key_response, ("publicKey", "public_key"))
    private_key = _find_string(key_response, ("privateKey", "private_key"))
    parsed_public = serialization.load_pem_public_key(public_key.encode("ascii"))
    parsed_private = serialization.load_pem_private_key(
        private_key.encode("ascii"), password=None
    )
    if not isinstance(parsed_public, rsa.RSAPublicKey) or not isinstance(
        parsed_private, rsa.RSAPrivateKey
    ):
        raise ValueError("PLAUD gen-key did not return an RSA key pair")
    if parsed_public.public_numbers() != parsed_private.public_key().public_numbers():
        raise ValueError("PLAUD gen-key public/private keys do not match")

    sign_response = _post(
        f"{credentials.base_url}/open/partner/sdk/sn-sign",
        headers=bearer_headers,
        body=json.dumps(
            {"type": "notepro", "sn": serial}, separators=(",", ":")
        ).encode("utf-8"),
    )
    signature = _find_string(sign_response, ("signature",))
    if len(base64.b64decode(signature, validate=True)) != 256:
        raise ValueError("PLAUD SN signature has an unexpected size")

    save_device_material(
        state_dir,
        {
            "version": 1,
            "sn": serial,
            "deviceType": "notepro",
            "deviceNameHint": "Plaud Note Pro",
            "signature": signature,
            "bindToken": bind_token,
            "rsaPublicKey": public_key,
            "rsaPrivateKey": private_key,
        },
    )
    expires = int(claims.get("exp", 0) or 0)
    return {
        "serialSuffix": serial[-4:],
        "deviceType": "notepro",
        "jwtExpiresAt": datetime.fromtimestamp(expires, timezone.utc).isoformat()
        if expires
        else None,
        "credentialsStoredWithDpapi": True,
    }
