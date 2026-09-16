package com.plaud.template.hub.device.feishu

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class D3200FoundDevice(val name: String, val address: String, val rssi: Int)

data class D3200DeviceInfo(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val firmwareVersion: String? = null,
    val serialNumber: String? = null,
    val totalMemoryKb: Long? = null,
    val freeMemoryKb: Long? = null,
    val boxBattery: Int? = null,
    val boxCharging: Boolean? = null,
    val recordStatus: Int = 0
)

data class D3200Recording(
    val fileId: Long,
    val endTime: Long?,
    val sizeBytes: Long,
    val estimatedDurationMs: Long
)

/** Native Android implementation of the verified soundcore Work / Feishu D3200 protocol. */
@SuppressLint("MissingPermission")
class D3200Client(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager.adapter ?: error("手机不支持蓝牙")
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val packets = Channel<Packet>(Channel.UNLIMITED)
    private val crypto = D3200Crypto()
    private var packetBuffer = ByteArray(0)
    private var connectResult: CompletableDeferred<Unit>? = null
    private var servicesResult: CompletableDeferred<Unit>? = null
    private var mtuResult: CompletableDeferred<Unit>? = null
    private var operationResult: CompletableDeferred<Unit>? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private data class Packet(
        val raw: ByteArray,
        val status: Int,
        val cmdType: Int,
        val cmdId: Int,
        val payload: ByteArray
    )

    suspend fun scan(timeoutMs: Long = 10_000): List<D3200FoundDevice> {
        requirePermissions()
        val scanner = adapter.bluetoothLeScanner ?: error("请先开启蓝牙")
        val found = linkedMapOf<String, D3200FoundDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: "飞书录音豆 D3200"
                found[result.device.address] = D3200FoundDevice(name, result.device.address, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                packets.trySend(Packet(ByteArray(0), errorCode, -1, -1, ByteArray(0)))
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try { delay(timeoutMs) } finally { scanner.stopScan(callback) }
        return found.values.sortedByDescending { it.rssi }
    }

    suspend fun connect(address: String) {
        requirePermissions()
        close()
        connectResult = CompletableDeferred()
        gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15_000) { connectResult!!.await() }
        mtuResult = CompletableDeferred()
        if (gatt!!.requestMtu(517)) runCatching { withTimeout(5_000) { mtuResult!!.await() } }
        servicesResult = CompletableDeferred()
        check(gatt!!.discoverServices()) { "无法发现录音豆服务" }
        withTimeout(12_000) { servicesResult!!.await() }
        enableNotifications()
        _connectionState.value = true
    }

    suspend fun readDeviceInfo(): D3200DeviceInfo {
        write(command(0x01, 0x01))
        return parseDeviceInfo(receive(0x01, 0x01, 8_000).payload)
    }

    suspend fun listRecordings(): List<D3200Recording> {
        val collected = linkedMapOf<Long, D3200Recording>()
        repeat(50) { page ->
            write(command(0x1b, 0x0e, littleEndian16(page)))
            val files = parseFileList(receive(0x1b, 0x0e, 8_000).payload)
            files.forEach { collected[it.fileId] = it }
            if (files.size < 10) return collected.values.sortedByDescending { it.fileId }
        }
        return collected.values.sortedByDescending { it.fileId }
    }

    suspend fun startRecording() {
        write(command(0x18, 0x82, byteArrayOf(1)))
    }

    suspend fun pauseRecording() {
        write(command(0x18, 0x82, byteArrayOf(2)))
    }

    suspend fun deleteRecording(fileId: Long) {
        write(command(0x1a, 0x10, littleEndian32(fileId)))
        val response = receive(0x1a, 0x10, 10_000)
        check(response.status == 0) { "录音豆拒绝删除（状态码 ${response.status}）" }
    }

    suspend fun downloadRecording(recording: D3200Recording, destination: File): File {
        ensureCrypto()
        crypto.clearFile(recording.fileId)
        val chunks = ArrayList<ByteArray>()
        var receivedBytes = 0L
        var expectedBytes = recording.sizeBytes
        var hasHeader = false
        try {
            write(command(0x1a, 0x07, ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(recording.fileId.toInt()).put(0).array()))
            withTimeout(120_000) {
                while (true) {
                    val packet = packets.receive()
                    if (packet.cmdType !in listOf(0x1a, 0x1b)) continue
                    when (packet.cmdId) {
                        0x07 -> {
                            val secret = crypto.prepareFile(packet.payload)
                            if (secret.fileId == recording.fileId) {
                                expectedBytes = secret.fileSize.takeIf { it > 0 } ?: expectedBytes
                                hasHeader = true
                            }
                        }
                        0x08, 0x12 -> if (hasHeader) {
                            consumeEncryptedSlices(packet.raw, recording.fileId).forEach {
                                chunks += it
                                receivedBytes += it.size
                            }
                        }
                        0x0a -> break
                    }
                }
            }
            require(hasHeader && chunks.isNotEmpty()) { "设备没有返回完整的可解密录音" }
            val raw = ByteArrayOutputStream().apply { chunks.forEach(::write) }.toByteArray()
            destination.parentFile?.mkdirs()
            destination.writeBytes(D3200Ogg.mux(raw))
            return destination
        } finally {
            crypto.clearFile(recording.fileId)
        }
    }

    fun close() {
        _connectionState.value = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        packetBuffer = ByteArray(0)
        crypto.reset()
        while (packets.tryReceive().isSuccess) Unit
    }

    private suspend fun ensureCrypto() {
        if (crypto.hasSession) return
        write(command(0x2e, 0x01, crypto.publicKey()))
        val response = receive(0x2e, 0x01, 12_000)
        check(crypto.completeHandshake(response.payload)) { "ECDH 握手校验失败" }
    }

    private fun consumeEncryptedSlices(raw: ByteArray, fileId: Long): List<ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 9
        while (raw.size - offset >= 165) {
            val sequence = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            offset += 5
            val encrypted = raw.copyOfRange(offset, offset + 160)
            offset += 160
            output += crypto.decryptChunk(fileId, sequence, encrypted)
            if (offset < raw.size) offset++
        }
        return output
    }

    private suspend fun receive(cmdType: Int, cmdId: Int, timeoutMs: Long): Packet = withTimeout(timeoutMs) {
        while (true) {
            val packet = packets.receive()
            if (packet.cmdType == cmdType && packet.cmdId == cmdId) return@withTimeout packet
        }
        error("unreachable")
    }

    private suspend fun write(value: ByteArray) {
        val currentGatt = gatt ?: error("录音豆尚未连接")
        val characteristic = writeCharacteristic ?: error("录音豆写入特征不存在")
        val withoutResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!withoutResponse) operationResult = CompletableDeferred()
        val writeType = if (withoutResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        check(started) { "录音豆 BLE 写入启动失败" }
        if (withoutResponse) delay(80) else withTimeout(10_000) { operationResult!!.await() }
    }

    private suspend fun enableNotifications() {
        val currentGatt = gatt ?: error("录音豆尚未连接")
        val service = currentGatt.getService(SERVICE_UUID) ?: error("不是兼容的 D3200：缺少服务")
        writeCharacteristic = service.getCharacteristic(WRITE_UUID) ?: error("D3200 缺少写入特征")
        val notify = service.getCharacteristic(NOTIFY_UUID) ?: error("D3200 缺少通知特征")
        check(currentGatt.setCharacteristicNotification(notify, true)) { "无法开启 D3200 通知" }
        val descriptor = notify.getDescriptor(CCCD_UUID) ?: error("D3200 通知描述符不存在")
        operationResult = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            currentGatt.writeDescriptor(descriptor)
        }
        check(started) { "无法写入 D3200 通知描述符" }
        withTimeout(10_000) { operationResult!!.await() }
    }

    private fun consume(fragment: ByteArray) {
        packetBuffer += fragment
        while (packetBuffer.size >= 10) {
            val start = findHeader(packetBuffer)
            if (start < 0) {
                packetBuffer = ByteArray(0)
                return
            }
            if (start > 0) packetBuffer = packetBuffer.copyOfRange(start, packetBuffer.size)
            if (packetBuffer.size < 10) return
            val total = (packetBuffer[7].toInt() and 0xff) or ((packetBuffer[8].toInt() and 0xff) shl 8)
            if (total < 10) {
                packetBuffer = packetBuffer.copyOfRange(1, packetBuffer.size)
                continue
            }
            if (packetBuffer.size < total) return
            val raw = packetBuffer.copyOfRange(0, total)
            packetBuffer = packetBuffer.copyOfRange(total, packetBuffer.size)
            if (checksum(raw.copyOf(raw.size - 1)) != raw.last()) continue
            packets.trySend(Packet(
                raw = raw,
                status = raw[4].toInt() and 0xff,
                cmdType = raw[5].toInt() and 0xff,
                cmdId = raw[6].toInt() and 0xff,
                payload = raw.copyOfRange(9, raw.size - 1)
            ))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // A disconnect callback from the previous GATT can arrive after a reconnect started.
            // Ignore that stale object so it cannot mark the new application-scoped session down.
            if (gatt !== this@D3200Client.gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connectResult?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = false
                connectResult?.completeExceptionally(IllegalStateException("录音豆已断开（$status）"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { mtuResult?.complete(Unit) }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesResult?.complete(Unit)
            else servicesResult?.completeExceptionally(IllegalStateException("D3200 服务发现失败：$status"))
        }

        @Deprecated("API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            consume(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) operationResult?.complete(Unit)
            else operationResult?.completeExceptionally(IllegalStateException("D3200 写入失败：$status"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) operationResult?.complete(Unit)
            else operationResult?.completeExceptionally(IllegalStateException("D3200 通知开启失败：$status"))
        }
    }

    private fun parseDeviceInfo(payload: ByteArray): D3200DeviceInfo {
        if (payload.size < 3) return D3200DeviceInfo()
        var offset = 0
        offset++ // connection status
        val battery = batteryPercent(payload[offset++])
        val charging = payload[offset++].toInt() == 1
        fun ascii(length: Int): String? {
            if (payload.size < offset + length) return null
            val value = payload.copyOfRange(offset, offset + length)
                .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
            offset += length
            return value.ifBlank { null }
        }
        val firmware = ascii(5)
        val serial = ascii(16)?.lowercase()
        var total: Long? = null
        var free: Long? = null
        if (payload.size >= offset + 8) {
            val view = ByteBuffer.wrap(payload, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
            total = view.int.toLong() and 0xffffffffL
            free = view.int.toLong() and 0xffffffffL
            offset += 8
        }
        val boxCharging = if (payload.size > offset) payload[offset++].toInt() == 1 else null
        ascii(5) // case firmware
        val boxBattery = if (payload.size > offset) batteryPercent(payload[offset++]) else null
        if (payload.size >= offset + 6) offset += 6 // case MAC
        var recordStatus = 0
        if (payload.size >= offset + 6) {
            offset += 5
            recordStatus = if (payload[offset].toInt() == 1) 1 else 0
        }
        return D3200DeviceInfo(battery, charging, firmware, serial, total, free, boxBattery, boxCharging, recordStatus)
    }

    private fun parseFileList(payload: ByteArray): List<D3200Recording> {
        if (payload.size < 2) return emptyList()
        val view = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = view.short.toInt() and 0xffff
        val output = ArrayList<D3200Recording>()
        repeat(count) {
            if (view.remaining() < 12) return@repeat
            val fileId = view.int.toLong() and 0xffffffffL
            val endTime = view.int.toLong() and 0xffffffffL
            val size = view.int.toLong() and 0xffffffffL
            if (size > 0) {
                val duration = if (endTime >= fileId) {
                    val clock = (endTime - fileId) * 1000
                    val ratio = if (size > 0) clock.toDouble() / size else 0.0
                    if (ratio in 0.5..2.0) clock else size
                } else size
                output += D3200Recording(fileId, endTime, size, duration)
            }
        }
        return output
    }

    private fun batteryPercent(rawByte: Byte): Int {
        val value = (rawByte.toInt() and 0xff) and 0x7f
        return if (value <= 9) (value + 1) * 10 else value.coerceAtMost(100)
    }

    private fun command(cmdType: Int, cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val bytes = ByteArray(10 + payload.size)
        byteArrayOf(0x08, 0xee.toByte(), 0, 0, 0, cmdType.toByte(), cmdId.toByte()).copyInto(bytes)
        bytes[7] = (bytes.size and 0xff).toByte()
        bytes[8] = ((bytes.size ushr 8) and 0xff).toByte()
        payload.copyInto(bytes, 9)
        bytes[bytes.lastIndex] = checksum(bytes.copyOf(bytes.lastIndex))
        return bytes
    }

    private fun checksum(bytes: ByteArray): Byte {
        var value = 0
        bytes.forEach { value = (value + (it.toInt() and 0xff)) and 0xff }
        return value.toByte()
    }

    private fun findHeader(bytes: ByteArray): Int {
        for (index in 0..bytes.size - 4) {
            if (bytes[index] == 0x09.toByte() && bytes[index + 1] == 0xff.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 0.toByte()) return index
        }
        return -1
    }

    private fun littleEndian16(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun littleEndian32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun requirePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "请允许附近设备权限"
            }
        } else {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "请允许定位权限以搜索 BLE 设备"
            }
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("020cf5da-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("00007777-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("00008888-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
