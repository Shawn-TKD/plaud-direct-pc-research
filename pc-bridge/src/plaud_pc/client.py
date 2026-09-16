from __future__ import annotations

import asyncio
import os
import struct
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidTag

from .protocol import (
    CMD_PRE_RSA,
    CMD_PRE_SIGNATURE_CONFIRM,
    ProtocolError,
    SessionCrypto,
    build_handshake_frame,
    parse_file_data_body,
    rsa_public_key_chunks,
    signature_chunks,
)

SERVICE_UUID = "00001910-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "00002bb0-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "00002bb1-0000-1000-8000-00805f9b34fb"


@dataclass(frozen=True)
class DeviceFile:
    session_id: int
    size: int
    scene: int
    marked: bool


@dataclass(frozen=True)
class ReadOnlySnapshot:
    name: str
    battery_percent: int
    charging: bool
    free_bytes: int
    total_bytes: int
    recording_seconds: int
    files: tuple[DeviceFile, ...]


@dataclass(frozen=True)
class DownloadResult:
    path: Path
    file: DeviceFile
    crc: int


class PlaudBleClient:
    def __init__(self, material: dict[str, Any]) -> None:
        self.material = material
        self._notifications: asyncio.Queue[bytes] = asyncio.Queue()
        self._session: SessionCrypto | None = None

    def _matches_device(self, candidate: Any, advertisement: Any) -> bool:
        uuids = {value.lower() for value in advertisement.service_uuids}
        name = (candidate.name or advertisement.local_name or "").lower()
        hint = str(self.material.get("deviceNameHint", "")).lower()
        if hint and hint not in name:
            return False
        return SERVICE_UUID in uuids or "plaud" in name

    async def _write(self, client: Any, packet: bytes) -> None:
        await client.write_gatt_char(WRITE_UUID, packet, response=True)
        await asyncio.sleep(0.025)

    async def _wait_raw_command(self, commands: set[int], timeout: float = 15.0) -> bytes:
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError(f"timed out waiting for pre-handshake command {commands}")
            packet = await asyncio.wait_for(self._notifications.get(), remaining)
            if len(packet) >= 2 and struct.unpack_from("<H", packet)[0] in commands:
                return packet

    async def _collect_rsa_response(self, first: bytes, timeout: float = 20.0) -> list[bytes]:
        frames: dict[int, bytes] = {}
        count: int | None = None
        deadline = asyncio.get_running_loop().time() + timeout
        packet = first
        while True:
            if len(packet) >= 4 and struct.unpack_from("<H", packet)[0] == CMD_PRE_RSA:
                frame_count, index = packet[2], packet[3]
                if frame_count == 0 or index >= frame_count:
                    raise ProtocolError("invalid RSA response chunk")
                if count is not None and count != frame_count:
                    raise ProtocolError("RSA response chunk count changed")
                count = frame_count
                frames[index] = packet
                if len(frames) == count:
                    return [frames[index] for index in range(count)]
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError("timed out receiving RSA session material")
            packet = await asyncio.wait_for(self._notifications.get(), remaining)

    async def authenticate(self, client: Any) -> tuple[int, int]:
        for frame in signature_chunks(str(self.material["signature"])):
            await self._write(client, frame)

        acknowledgement = await self._wait_raw_command(
            {CMD_PRE_SIGNATURE_CONFIRM, CMD_PRE_RSA}, timeout=20.0
        )
        ack_command = struct.unpack_from("<H", acknowledgement)[0]
        if ack_command == CMD_PRE_SIGNATURE_CONFIRM:
            for frame in rsa_public_key_chunks(str(self.material["rsaPublicKey"])):
                await self._write(client, frame)
            first_rsa = await self._wait_raw_command({CMD_PRE_RSA}, timeout=20.0)
        else:
            first_rsa = acknowledgement

        rsa_frames = await self._collect_rsa_response(first_rsa)
        self._session = SessionCrypto.from_rsa_response(
            rsa_frames, str(self.material["rsaPrivateKey"])
        )
        handshake = build_handshake_frame(str(self.material["bindToken"]), port_version=20)
        await self._write(client, self._session.encrypt_body(handshake))
        command, payload = await self._wait_command(1, timeout=15.0)
        if not payload:
            raise ProtocolError("empty handshake response")
        status = payload[0]
        port_version = payload[1] if len(payload) > 1 else 0
        if status != 0:
            raise ProtocolError(f"device rejected handshake with status {status}")
        return command, port_version

    async def _wait_command(self, expected: int, timeout: float = 10.0) -> tuple[int, bytes]:
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError(f"timed out waiting for command {expected}")
            body = await self._wait_decrypted_body(remaining)
            if len(body) >= 3 and body[0] == 1:
                command = struct.unpack_from("<H", body, 1)[0]
                if command == expected:
                    return command, body[3:]

    async def _wait_decrypted_body(self, timeout: float = 10.0) -> bytes:
        if self._session is None:
            raise ProtocolError("session is not authenticated")
        deadline = asyncio.get_running_loop().time() + timeout
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError("timed out waiting for encrypted device data")
            packet = await asyncio.wait_for(self._notifications.get(), remaining)
            try:
                _, body = self._session.decrypt_packet(packet)
            # NotePin S can retransmit the final raw RSA chunks after the ChaCha session has
            # already been established. The official Android SDK logs BAD_DECRYPT for these and
            # continues until the real encrypted command arrives; mirror that behaviour here.
            except (ProtocolError, InvalidTag):
                continue
            return body

    async def request(self, client: Any, command: int, payload: bytes = b"") -> bytes:
        if self._session is None:
            raise ProtocolError("session is not authenticated")
        await self._write(client, self._session.encrypt_command(command, payload))
        _, response = await self._wait_command(command)
        return response

    async def read_snapshot(self, client: Any, name: str) -> ReadOnlySnapshot:
        battery = await self.request(client, 9)
        if len(battery) < 2:
            raise ProtocolError("short battery response")
        charging = battery[0] == 1
        battery_percent = battery[1]

        storage = await self.request(client, 6)
        if len(storage) < 16:
            raise ProtocolError("short storage response")
        free_bytes, total_bytes = struct.unpack_from("<QQ", storage)
        recording_seconds = struct.unpack_from("<Q", storage, 16)[0] if len(storage) >= 24 else 0

        uid = int(time.time()) & 0xFFFFFFFF
        await self._write(
            client,
            self._session.encrypt_command(26, struct.pack("<IIB", uid, 0, 0)),
        )
        files: list[DeviceFile] = []
        total_files: int | None = None
        deadline = asyncio.get_running_loop().time() + 15.0
        while total_files is None or len(files) < total_files:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise TimeoutError("timed out receiving file list")
            _, payload = await self._wait_command(26, timeout=remaining)
            if len(payload) < 8 or struct.unpack_from("<I", payload)[0] != uid:
                continue
            total_files = struct.unpack_from("<H", payload, 4)[0]
            start = struct.unpack_from("<H", payload, 6)[0]
            if start != len(files):
                raise ProtocolError("non-contiguous file list response")
            offset = 8
            while offset + 10 <= len(payload) and len(files) < total_files:
                session_id, size, scene, marked = struct.unpack_from("<IIBB", payload, offset)
                files.append(DeviceFile(session_id, size, scene, marked != 0))
                offset += 10

        return ReadOnlySnapshot(
            name=name,
            battery_percent=battery_percent,
            charging=charging,
            free_bytes=free_bytes,
            total_bytes=total_bytes,
            recording_seconds=recording_seconds,
            files=tuple(files),
        )

    async def download_file(
        self,
        client: Any,
        item: DeviceFile,
        output_dir: Path,
        *,
        port_version: int,
    ) -> DownloadResult:
        if self._session is None:
            raise ProtocolError("session is not authenticated")
        output_dir.mkdir(parents=True, exist_ok=True)
        target = output_dir / f"{item.session_id}.plaud"
        partial = output_dir / f"{item.session_id}.plaud.part"
        if target.exists():
            if target.stat().st_size != item.size:
                raise ProtocolError("existing completed file has the wrong size")
            return DownloadResult(target, item, 0)
        position = partial.stat().st_size if partial.exists() else 0
        if position > item.size:
            raise ProtocolError("partial file is larger than the device recording")

        await self._write(
            client,
            self._session.encrypt_command(
                # The official SDK uses end=0 for a complete/resumable recording sync.
                28, struct.pack("<III", item.session_id, position, 0)
            ),
        )
        head_ok = False
        crc: int | None = None
        with partial.open("ab") as stream:
            while crc is None:
                body = await self._wait_decrypted_body(20.0)
                if body[0] == 1 and len(body) >= 3:
                    command = struct.unpack_from("<H", body, 1)[0]
                    payload = body[3:]
                    if command == 28:
                        if len(payload) < 5:
                            raise ProtocolError("short file-download head")
                        session_id, status = struct.unpack_from("<IB", payload)
                        if session_id != item.session_id or status != 0:
                            raise ProtocolError(
                                f"device rejected file download (session={session_id}, status={status})"
                            )
                        head_ok = True
                    elif command == 29:
                        if len(payload) < 6:
                            raise ProtocolError("short file-download tail")
                        session_id, crc = struct.unpack_from("<IH", payload)
                        if session_id != item.session_id:
                            raise ProtocolError("file-download tail session mismatch")
                elif body[0] == 2:
                    if not head_ok:
                        raise ProtocolError("file data arrived before download acknowledgement")
                    frame = parse_file_data_body(body, port_version=port_version)
                    if frame.session_id not in (0, item.session_id):
                        raise ProtocolError("file-data session mismatch")
                    if frame.offset == 0xFFFFFFFF:
                        # Firmware emits EMPTY_PKG + a completion code before CMD 29.
                        # The official SDK calls finish(code) and continues waiting for the tail.
                        continue
                    if frame.offset != position:
                        raise ProtocolError(
                            f"non-contiguous file data: expected {position}, got {frame.offset}"
                        )
                    if position + len(frame.data) > item.size:
                        raise ProtocolError("device sent data beyond the advertised file size")
                    stream.write(frame.data)
                    stream.flush()
                    position += len(frame.data)

        if position != item.size:
            raise ProtocolError(
                f"download ended at {position} bytes, expected {item.size}"
            )
        os.replace(partial, target)
        return DownloadResult(target, item, crc)

    async def connect_read_only(self, scan_seconds: float = 60.0) -> ReadOnlySnapshot:
        from bleak import BleakClient, BleakScanner
        from bleak.exc import BleakError

        last_error: Exception | None = None
        for attempt in range(3):
            device = await BleakScanner.find_device_by_filter(
                self._matches_device,
                timeout=scan_seconds if attempt == 0 else 20.0,
            )
            if device is None:
                last_error = TimeoutError("No Plaud advertisement found")
                continue
            try:
                # NotePin rotates BLE addresses and Windows can retain a stale GATT service table
                # for the previous address. Force fresh service discovery on every attempt.
                async with BleakClient(
                    device,
                    timeout=30.0,
                    winrt={"use_cached_services": False},
                ) as client:
                    if not client.is_connected:
                        raise ConnectionError("Windows connected but GATT is unavailable")

                    def on_notification(_: Any, data: bytearray) -> None:
                        self._notifications.put_nowait(bytes(data))

                    await client.start_notify(NOTIFY_UUID, on_notification)
                    await self.authenticate(client)
                    snapshot = await self.read_snapshot(client, device.name or "Plaud NotePin")
                    await client.stop_notify(NOTIFY_UUID)
                    return snapshot
            except BleakError as error:
                last_error = error
                await asyncio.sleep(2.0)
        raise ConnectionError(f"Unable to open NotePin GATT after retries: {last_error}")

    async def connect_download_latest(
        self, output_dir: Path, scan_seconds: float = 60.0
    ) -> tuple[ReadOnlySnapshot, DownloadResult]:
        """Download the newest advertised recording without deleting it from the device."""
        from bleak import BleakClient, BleakScanner
        from bleak.exc import BleakError

        last_error: Exception | None = None
        for attempt in range(3):
            device = await BleakScanner.find_device_by_filter(
                self._matches_device,
                timeout=scan_seconds if attempt == 0 else 20.0,
            )
            if device is None:
                last_error = TimeoutError("No Plaud advertisement found")
                continue
            try:
                async with BleakClient(
                    device,
                    timeout=30.0,
                    winrt={"use_cached_services": False},
                ) as client:
                    if not client.is_connected:
                        raise ConnectionError("Windows connected but GATT is unavailable")

                    def on_notification(_: Any, data: bytearray) -> None:
                        self._notifications.put_nowait(bytes(data))

                    await client.start_notify(NOTIFY_UUID, on_notification)
                    _, port_version = await self.authenticate(client)
                    snapshot = await self.read_snapshot(client, device.name or "Plaud NotePin")
                    if not snapshot.files:
                        raise ProtocolError("device file list is empty")
                    latest = max(snapshot.files, key=lambda item: item.session_id)
                    result = await self.download_file(
                        client, latest, output_dir, port_version=port_version
                    )
                    await client.stop_notify(NOTIFY_UUID)
                    return snapshot, result
            except (BleakError, TimeoutError, ConnectionError) as error:
                last_error = error
                await asyncio.sleep(2.0)
        raise ConnectionError(f"Unable to download from NotePin after retries: {last_error}")
