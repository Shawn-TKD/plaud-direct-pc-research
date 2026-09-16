from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Mapping


@dataclass(frozen=True)
class PlaudAdvertisement:
    serial_number: str
    project_code: int
    version_name: str
    port_version: int | None


def parse_manufacturer_data(values: Mapping[int, bytes]) -> PlaudAdvertisement | None:
    """Mirror the public SDK's PkgUtils parser for PLAUD BLE advertisements."""
    if not values:
        return None
    data = next(iter(values.values()))
    try:
        index = 0
        version_field_size = data[index]
        index += 1
        project_code = 0
        if version_field_size == 2:
            project_code = int.from_bytes(data[index : index + 2], "little")
            index += 2
            version_field_size = data[index]
            index += 1
        version_type = chr(data[index])
        index += 1
        version_code_size = version_field_size - 1
        version_code = int.from_bytes(
            data[index : index + version_code_size], "little"
        )
        index += version_code_size
        serial_size = data[index]
        index += 1
        raw_serial = data[index : index + serial_size].decode("utf-8", errors="ignore")
        serial = re.sub(r"[^a-zA-Z0-9_\-\u2e80-\u9fff]", "", raw_serial)
        if len(serial) < 3:
            return None
        if serial[:3].isdigit():
            project_code = int(serial[:3])

        port_offset = 18 if project_code in (881, 888) else 15
        port_version = (
            int.from_bytes(data[port_offset : port_offset + 2], "little")
            if len(data) >= port_offset + 2
            else None
        )
        return PlaudAdvertisement(
            serial_number=serial,
            project_code=project_code,
            version_name=f"{version_type}{version_code}",
            port_version=port_version,
        )
    except (IndexError, UnicodeError, ValueError):
        return None
