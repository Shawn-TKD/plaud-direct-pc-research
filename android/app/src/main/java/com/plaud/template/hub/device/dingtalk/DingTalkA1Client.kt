package com.plaud.template.hub.device.dingtalk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.system.Os
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class A1Credentials(val did: String, val corpId: String, val deviceSecret: String)
data class A1RemoteRecording(val fid: Long, val durationSeconds: Int, val flag: Int)

data class A1DeviceStatus(
    val audioStatus: String?,
    val activeFid: Long?,
    val durationSeconds: Int?,
    val batteryPercent: Int?,
    /** The firmware reports storage values in MiB. */
    val storageRemainingMb: Long?,
    val storageTotalMb: Long?,
    val firmwareVersion: String?,
    val batteryCasePercent: Int? = null,
    val batteryLeftPercent: Int? = null,
    val batteryRightPercent: Int? = null,
    val deviceRole: Int? = null,
    val peerOnline: Boolean? = null,
    val storageLeftRemainingMb: Long? = null,
    val storageLeftTotalMb: Long? = null,
    val storageRightRemainingMb: Long? = null,
    val storageRightTotalMb: Long? = null,
    val vendorVersionLeft: String? = null,
    val vendorVersionRight: String? = null
)

data class A1Inspection(
    val status: A1DeviceStatus,
    val capabilities: Map<String, Int>,
    val authentication: Map<String, Any?>,
    val graySwitches: Map<String, Int>,
    val batteryQuery: Map<String, Any?>
)

data class A1FileAttributes(
    val fid: Long,
    val codecAttributes: String?,
    val fileVersion: String?,
    /** 0=normal recording, 1=offline memo, 2=real stream (static SDK mapping). */
    val recordingType: Int?,
    /** Microphone/audio mode, not the recording type. */
    val streamType: Int?,
    val fileSync: Boolean?,
    val aes: Int?,
    val algorithmMode: Int?,
    val incognitoMode: Int?,
    val sid: Int?,
    val sizeBytes: Long?,
    val raw: Map<String, Any?>
)

enum class A1EventKind { RECORDING_STARTED, RECORDING_STOPPED, STREAM_ATTRIBUTES, MARKER, UT_REPORT }

data class A1DeviceEvent(
    val kind: A1EventKind,
    val frameType: Int,
    val command: Int,
    val fid: Long? = null,
    val relativeSeconds: Int? = null,
    val markerType: Int? = null,
    val recordingType: Int? = null,
    val streamType: Int? = null,
    val sampleRate: Int? = null,
    val telemetrySequence: Int? = null,
    val telemetryClass: Int? = null,
    val telemetryCode: Int? = null,
    val telemetryArgument0: Long? = null,
    val telemetryArgument1: Long? = null,
    val deviceTimestamp: Long? = null,
    val receivedAt: Long = System.currentTimeMillis()
)

data class A1LiveCapture(
    val fid: Long,
    val localFile: File,
    val durationSeconds: Double,
    val packetCount: Int,
    val sampleRate: Int,
    val recordingType: Int?,
    val streamType: Int?
)

/** Android port of the repository's verified, owner-credential DingTalk A1 BLE path. */
@SuppressLint("MissingPermission")
class DingTalkA1Client(private val context: Context) {
    private var wifiNetwork: android.net.Network? = null
    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null
    val wifiReady: Boolean get() = wifiNetwork != null
    val wifiSessionActive: Boolean get() = wifiCallback != null

    /** Experimental HTTP audio transport; never enables the OTA TCP service. */
    suspend fun openAudioWifi(credentials: A1Credentials) = protocolMutex.withLock {
        check(Build.VERSION.SDK_INT >= 29) { "Wi-Fi 直连需要 Android 10 或以上" }
        check(wifiCallback == null) { "请先关闭上一次 Wi-Fi 会话" }
        val reply = requestJson(0x0120, 0x30,
            JSONObject().put("did", credentials.did).put("type", 0), 30_000)
        check(reply.optInt("code") == 200) { "A1 未开启热点：${reply.optInt("code")}" }
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        try {
            check(reply.getString("ip") == "192.168.1.1") { "暂不支持该热点地址" }
            val ready = CompletableDeferred<Unit>()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { wifiNetwork = network; ready.complete(Unit) }
                override fun onUnavailable() { ready.completeExceptionally(java.io.IOException("热点连接未获批准或不可用")) }
                override fun onLost(network: android.net.Network) { if (wifiNetwork == network) wifiNetwork = null }
            }
            wifiCallback = callback
            val spec = android.net.wifi.WifiNetworkSpecifier.Builder()
                .setSsid(reply.getString("ssid")).setWpa2Passphrase(reply.getString("passwd")).build()
            manager.requestNetwork(android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build(), callback)
            withTimeout(60_000) { ready.await() }
        } catch (error: Exception) {
            releaseAudioWifi()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { requestJson(0x0121, 0x31, JSONObject().put("did", credentials.did), 10_000) }
            }
            throw error
        }
    }

    fun releaseAudioWifi() {
        wifiCallback?.let { context.getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiCallback = null
        wifiNetwork = null
    }

    suspend fun closeAudioWifi(credentials: A1Credentials) = protocolMutex.withLock {
        try {
            val response = requestJson(0x0121, 0x31, JSONObject().put("did", credentials.did), 15_000)
            check(response.optInt("code") in listOf(200, 202)) { "设备未确认关闭热点" }
        } finally { releaseAudioWifi() }
    }

    private fun downloadWifi(item: A1RemoteRecording, destination: File,
        onProgress: ((Long, Long?) -> Unit)?): File {
        val network = wifiNetwork ?: error("A1 Wi-Fi 已断开，请关闭高速模式后用 BLE 重试")
        val url = java.net.URL("http://192.168.1.1/audio/" + String.format(java.util.Locale.US, "%014d", item.fid))
        val connection = network.openConnection(url) as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        destination.parentFile?.mkdirs()
        val raw = File(destination.parentFile, ".${destination.name}.wifi-raw")
        val converted = File(destination.parentFile, ".${destination.name}.wifi-part")
        try {
            check(connection.responseCode == 200) { "Wi-Fi 下载 HTTP ${connection.responseCode}" }
            val total = connection.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
            var received = 0L
            connection.inputStream.use { input -> raw.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    received += count
                    onProgress?.invoke(received, total)
                }
            } }
            check(received > 0 && (total == null || total == received)) { "Wi-Fi 文件未完整接收" }
            check(raw.startsWith(BABA_MAGIC)) { "未知录音格式，未导入；请使用 BLE 下载" }
            DtyjOgg.convert(raw, converted, item.fid.toInt())
            Os.rename(converted.absolutePath, destination.absolutePath)
            return destination
        } finally { connection.disconnect(); raw.delete(); converted.delete() }
    }
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager.adapter ?: error("手机不支持蓝牙")
    @Volatile
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    /**
     * One queue per command is important: the A1 can push marker/stream frames while a file-list
     * response is being awaited. A single consuming queue used to silently discard those frames.
     */
    private val pendingFrames = ConcurrentHashMap<Int, Channel<Frame>>()
    private val protocolMutex = Mutex()
    private var incoming = ByteArray(0)
    private var connectResult: CompletableDeferred<Unit>? = null
    private var servicesResult: CompletableDeferred<Unit>? = null
    private var mtuResult: CompletableDeferred<Unit>? = null
    private var operationResult: CompletableDeferred<Unit>? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow<A1DeviceStatus?>(null)
    val deviceStatus: StateFlow<A1DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<String, Int>>(emptyMap())
    val capabilities: StateFlow<Map<String, Int>> = _capabilities.asStateFlow()

    private val _authentication = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val authentication: StateFlow<Map<String, Any?>> = _authentication.asStateFlow()

    private val _fileAttributes = MutableStateFlow<Map<Long, A1FileAttributes>>(emptyMap())
    val fileAttributes: StateFlow<Map<Long, A1FileAttributes>> = _fileAttributes.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<A1DeviceEvent>>(emptyList())
    val recentEvents: StateFlow<List<A1DeviceEvent>> = _recentEvents.asStateFlow()

    private val _events = MutableSharedFlow<A1DeviceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<A1DeviceEvent> = _events.asSharedFlow()

    private val _completedCaptures = MutableSharedFlow<A1LiveCapture>(extraBufferCapacity = 8)
    val completedCaptures: SharedFlow<A1LiveCapture> = _completedCaptures.asSharedFlow()

    private var activeRecordingFid: Long? = null
    private var currentCapture: CaptureBuilder? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class FoundDevice(val name: String, val address: String, val rssi: Int)
    private data class Frame(val type: Int, val command: Int, val sequence: Int, val payload: ByteArray)
    private data class CaptureBuilder(
        val fid: Long,
        var recordingType: Int? = null,
        var streamType: Int? = null,
        var sampleRate: Int = 16_000,
        val packets: MutableList<ByteArray> = mutableListOf(),
        val seenBlocks: MutableSet<String> = mutableSetOf()
    )

    @SuppressLint("MissingPermission")
    suspend fun scan(timeoutMs: Long = 10_000): List<FoundDevice> {
        requirePermissions()
        val scanner = adapter.bluetoothLeScanner ?: error("请先开启蓝牙")
        val found = linkedMapOf<String, FoundDevice>()
        var scanFailure: Int? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = FoundDevice(
                    result.device.name ?: result.scanRecord?.deviceName ?: "DingTalk A1",
                    result.device.address,
                    result.rssi
                )
            }
            override fun onScanFailed(errorCode: Int) {
                scanFailure = errorCode
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        try { delay(timeoutMs) } finally { scanner.stopScan(callback) }
        scanFailure?.let { error("A1 蓝牙扫描失败：$it") }
        return found.values.sortedByDescending { it.rssi }
    }

    suspend fun connect(address: String, credentials: A1Credentials) = protocolMutex.withLock {
        requirePermissions()
        close()
        connectResult = CompletableDeferred()
        val connectingGatt = adapter.getRemoteDevice(address)
            .connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = connectingGatt
        try {
            withTimeout(15_000) { connectResult!!.await() }
            mtuResult = CompletableDeferred()
            if (connectingGatt.requestMtu(517)) {
                runCatching { withTimeout(5_000) { mtuResult!!.await() } }
            }
            servicesResult = CompletableDeferred()
            check(connectingGatt.discoverServices()) { "无法发现 A1 服务" }
            withTimeout(12_000) { servicesResult!!.await() }
            enableNotifications()
            authenticate(credentials)
            _connectionState.value = true
        } catch (error: Throwable) {
            closeFailedConnection(connectingGatt, error)
            throw error
        }
    }

    suspend fun listRecordings(credentials: A1Credentials): List<A1RemoteRecording> = protocolMutex.withLock {
        val payload = requestFrame(
            0x0110,
            0x16,
            JSONObject().put("did", credentials.did).put("s_fid", "0")
                .put("recently", 100).put("e_fid", "0"),
            20_000
        ).payload
        require(payload.size >= 4) { "A1 文件列表响应过短" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val code = buffer.short.toInt() and 0xffff
        val count = buffer.short.toInt() and 0xffff
        check(code == 200) { "A1 文件列表错误：$code" }
        require(payload.size >= 4 + count * 8) { "A1 文件列表不完整" }
        return List(count) {
            val flag = buffer.short.toInt() and 0xffff
            val fid = buffer.int.toLong() and 0xffffffffL
            val duration = buffer.short.toInt() and 0xffff
            A1RemoteRecording(fid, duration, flag)
        }
    }

    /** Read-only protocol inspection: status, capability switches and the battery control key. */
    suspend fun inspect(credentials: A1Credentials): A1Inspection = protocolMutex.withLock {
        val statusJson = requestJson(0x0132, 0x12, JSONObject().put("did", credentials.did), 20_000)
        check(statusJson.optInt("code", 200) == 200) {
            "A1 状态读取失败：${statusJson.optInt("code")}"
        }
        val status = parseStatus(statusJson)
        _deviceStatus.value = status

        val grayJson = requestJson(
            0x0137,
            0x14,
            JSONObject().put("did", credentials.did).put("action", "get"),
            20_000
        )
        check(grayJson.optInt("code", 200) == 200) {
            "A1 能力开关读取失败：${grayJson.optInt("code")}"
        }

        val batteryJson = requestJson(
            0x013F,
            0x15,
            JSONObject().put("did", credentials.did).put("key", "battery_key"),
            20_000
        )

        return A1Inspection(
            status = status,
            capabilities = _capabilities.value,
            authentication = _authentication.value,
            graySwitches = grayJson.toIntMap(exclude = setOf("code")),
            batteryQuery = batteryJson.toValueMap()
        )
    }

    suspend fun download(
        credentials: A1Credentials,
        item: A1RemoteRecording,
        destination: File,
        onProgress: ((receivedBytes: Long, totalBytes: Long?) -> Unit)? = null
    ): File = protocolMutex.withLock {
        if (wifiSessionActive) return@withLock downloadWifi(item, destination, onProgress)
        val acceptedFrames = beginResponse(0x0111)
        val attributeFrames = beginResponse(0x0114)
        val blockFrames = beginResponse(0x0115)
        destination.parentFile?.mkdirs()
        val rawTemp = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.download")
        val outputTemp = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
        try {
            send(0x0111, 0x20, JSONObject().put("did", credentials.did).put("fid", item.fid.toString())
                .put("offset", 0).put("progress", 65537))
            val accepted = JSONObject(String(awaitResponse(0x0111, acceptedFrames, 20_000).payload, Charsets.UTF_8))
            check(accepted.optInt("code") in listOf(200, 202)) { "A1 拒绝下载：${accepted.optInt("code")}" }

            val attrs = awaitResponse(0x0114, attributeFrames, 45_000)
            val attrsJson = runCatching { JSONObject(String(attrs.payload, Charsets.UTF_8)) }.getOrNull()
            val expected = attrsJson?.let(::findSize)
            attrsJson?.let { rememberFileAttributes(it, item.fid) }
            onProgress?.invoke(0L, expected)
            acknowledge(0x0114, attrs.sequence)
            val pendingBlocks = sortedMapOf<Int, ByteArray>()
            val seenBlocks = hashSetOf<Int>()
            var nextBlockNumber: Int? = null
            var received = 0L
            FileOutputStream(rawTemp).use { rawOutput ->
                while (expected == null || received < expected) {
                    val frame = awaitResponse(0x0115, blockFrames, 90_000)
                    require(frame.payload.size >= 16) { "A1 文件块过短" }
                    val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN)
                    b.short
                    val fid = b.int.toLong() and 0xffffffffL
                    b.short
                    val number = b.int
                    val length = b.int
                    require(fid == item.fid && length >= 0 && frame.payload.size >= 16 + length) {
                        "A1 文件块异常"
                    }
                    if (seenBlocks.add(number)) {
                        if (nextBlockNumber == null) nextBlockNumber = number
                        require(number >= nextBlockNumber!!) { "A1 文件块乱序：$number" }
                        pendingBlocks[number] = frame.payload.copyOfRange(16, 16 + length)
                        received += length
                        while (true) {
                            val next = nextBlockNumber ?: break
                            val bytes = pendingBlocks.remove(next) ?: break
                            rawOutput.write(bytes)
                            nextBlockNumber = next + 1
                        }
                        onProgress?.invoke(received, expected)
                    }
                    acknowledge(0x0115, frame.sequence)
                    if (expected == null && length < 48_000) break
                }
                require(pendingBlocks.isEmpty()) { "A1 文件块不连续" }
                rawOutput.fd.sync()
            }
            val rawSize = rawTemp.length()
            if (expected != null) require(rawSize == expected) { "A1 文件大小不一致" }
            if (rawTemp.startsWith(BABA_MAGIC)) {
                DtyjOgg.convert(rawTemp, outputTemp, item.fid.toInt())
            } else {
                FileInputStream(rawTemp).use { input ->
                    FileOutputStream(outputTemp).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            Os.rename(outputTemp.absolutePath, destination.absolutePath)
            onProgress?.invoke(expected ?: rawSize, expected ?: rawSize)
            destination
        } finally {
            rawTemp.delete()
            outputTemp.delete()
            endResponse(0x0111, acceptedFrames)
            endResponse(0x0114, attributeFrames)
            endResponse(0x0115, blockFrames)
        }
    }

    /** Permanently deletes one long recording from the authenticated owner's A1. */
    suspend fun deleteRecording(
        credentials: A1Credentials,
        item: A1RemoteRecording
    ) = protocolMutex.withLock {
        val response = requestJson(
            0x0113,
            0x30,
            JSONObject().put("did", credentials.did).put("fid", item.fid.toString()),
            20_000
        )
        check(response.optInt("code") == 200) {
            "A1 删除失败：${response.optInt("code")}"
        }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        commandCharacteristic = null
        incoming = ByteArray(0)
        failPending(IllegalStateException("A1 连接已关闭"))
        currentCapture = null
        activeRecordingFid = null
        _connectionState.value = false
    }

    private suspend fun authenticate(c: A1Credentials) {
        require(c.did.isNotBlank() && c.corpId.isNotBlank() && c.deviceSecret.length >= 16)
        val challenge = requestJson(
            0x0008,
            0x10,
            JSONObject().put("did", c.did).put("corp_id", c.corpId),
            15_000
        ).getString("random")
        val token = makeToken(c.deviceSecret, challenge)
        val body = JSONObject().put("did", c.did).put("model", "Android_A1_Client")
            .put("sdk_ver", "V2.1.3").put("corp_id", c.corpId).put("token", token)
            .put("timestamp", (System.currentTimeMillis() / 1000).toString())
        val response = requestJson(0x0133, 0x11, body, 20_000)
        check(response.optInt("code") == 200) { "A1 鉴权失败：${response.optInt("code")}" }
        _authentication.value = response.toValueMap()
        _capabilities.value = response.toIntMap(prefix = "cap_")
    }

    private suspend fun send(command: Int, sequence: Int, json: JSONObject, type: Int = 0x13) {
        write(frame(type, command, sequence, json.toString().toByteArray(Charsets.UTF_8)))
    }
    private suspend fun acknowledge(command: Int, sequence: Int) =
        send(command, sequence, JSONObject().put("code", 200), 0x31)

    private suspend fun requestFrame(
        command: Int,
        sequence: Int,
        body: JSONObject,
        timeoutMs: Long,
        type: Int = 0x13
    ): Frame {
        val response = beginResponse(command)
        return try {
            send(command, sequence, body, type)
            awaitResponse(command, response, timeoutMs)
        } finally {
            endResponse(command, response)
        }
    }

    private suspend fun requestJson(
        command: Int,
        sequence: Int,
        body: JSONObject,
        timeoutMs: Long,
        type: Int = 0x13
    ): JSONObject = JSONObject(String(requestFrame(command, sequence, body, timeoutMs, type).payload, Charsets.UTF_8))

    private fun beginResponse(command: Int): Channel<Frame> {
        val channel = Channel<Frame>(Channel.UNLIMITED)
        check(pendingFrames.putIfAbsent(command, channel) == null) {
            "A1 命令 0x${command.toString(16)} 正在执行"
        }
        return channel
    }

    private suspend fun awaitResponse(command: Int, channel: Channel<Frame>, timeoutMs: Long): Frame =
        withTimeout(timeoutMs) { channel.receive() }.also { frame ->
            check(frame.command == command) { "A1 响应路由异常" }
        }

    private fun endResponse(command: Int, channel: Channel<Frame>) {
        pendingFrames.remove(command, channel)
        channel.close()
    }

    private fun failPending(cause: Throwable) {
        pendingFrames.values.forEach { it.close(cause) }
        pendingFrames.clear()
    }

    private suspend fun write(value: ByteArray) {
        val currentGatt = gatt ?: error("A1 未连接")
        val characteristic = commandCharacteristic ?: error("A1 写入特征不存在")
        operationResult = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
        check(started) { "A1 BLE 写入启动失败" }
        withTimeout(12_000) { operationResult!!.await() }
    }

    private suspend fun enableNotifications() {
        val currentGatt = gatt ?: error("A1 未连接")
        val service = currentGatt.getService(SERVICE_UUID) ?: error("不是钉钉 A1：缺少服务")
        commandCharacteristic = service.getCharacteristic(COMMAND_UUID) ?: error("A1 缺少写入特征")
        val notify = service.getCharacteristic(NOTIFY_UUID) ?: error("A1 缺少通知特征")
        check(currentGatt.setCharacteristicNotification(notify, true)) { "无法开启 A1 通知" }
        val descriptor = notify.getDescriptor(CCCD_UUID) ?: error("A1 通知描述符不存在")
        operationResult = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            currentGatt.writeDescriptor(descriptor)
        }
        check(started) { "无法写入 A1 通知描述符" }
        withTimeout(10_000) { operationResult!!.await() }
    }

    private fun consume(data: ByteArray) {
        incoming += data
        while (incoming.size >= 8) {
            if (incoming[0].toInt() and 0xff !in listOf(0x13, 0x14, 0x31)) {
                incoming = incoming.copyOfRange(1, incoming.size); continue
            }
            val length = ByteBuffer.wrap(incoming, 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0 || length > 16 * 1024 * 1024) { incoming = incoming.copyOfRange(1, incoming.size); continue }
            if (incoming.size < 8 + length) return
            val type = incoming[0].toInt() and 0xff
            val command = ((incoming[1].toInt() and 0xff) shl 8) or (incoming[2].toInt() and 0xff)
            val frame = Frame(type, command, incoming[3].toInt() and 0xff, incoming.copyOfRange(8, 8 + length))
            pendingFrames[command]?.trySend(frame)
            handleDeviceFrame(frame)
            incoming = incoming.copyOfRange(8 + length, incoming.size)
        }
    }

    /** Parse only device-originated, already-observed formats; unknown commands remain queued. */
    private fun handleDeviceFrame(frame: Frame) {
        when (frame.command) {
            0x0132 -> parseJson(frame.payload)?.let { body ->
                if (body.optInt("code", 200) == 200) _deviceStatus.value = parseStatus(body)
            }
            0x0100 -> handleRecordEvent(frame.type, frame.payload)
            0x0102 -> handleRemark(frame.type, frame.payload)
            0x0116 -> handleStreamAttributes(frame.type, frame.payload)
            0x0117 -> handleAudioPush(frame.payload)
            0x000C -> handleUtReport(frame.type, frame.payload)
        }
    }

    private fun handleRecordEvent(frameType: Int, payload: ByteArray) {
        val body = parseJson(payload)?.eventBody() ?: return
        when (body.optString("action")) {
            "start" -> {
                val fid = body.optString("fid").toLongOrNull() ?: return
                val activeCapture = currentCapture
                if (activeCapture?.fid != fid) {
                    activeCapture?.takeIf { it.packets.isNotEmpty() }?.let(::finishCapture)
                    currentCapture = CaptureBuilder(fid)
                }
                activeRecordingFid = fid
                emitEvent(A1DeviceEvent(A1EventKind.RECORDING_STARTED, frameType, 0x0100, fid = fid))
            }
            "stop" -> {
                val capture = currentCapture
                emitEvent(A1DeviceEvent(
                    A1EventKind.RECORDING_STOPPED,
                    frameType,
                    0x0100,
                    fid = capture?.fid ?: activeRecordingFid
                ))
                currentCapture = null
                activeRecordingFid = null
                capture?.let(::finishCapture)
            }
        }
    }

    private fun handleStreamAttributes(frameType: Int, payload: ByteArray) {
        val body = parseJson(payload)?.eventBody() ?: return
        val fid = body.optString("fid").toLongOrNull() ?: activeRecordingFid ?: return
        val capture = currentCapture?.takeIf { it.fid == fid } ?: CaptureBuilder(fid).also {
            currentCapture = it
            activeRecordingFid = fid
        }
        capture.streamType = body.opt("stream_type")?.toString()?.toIntOrNull()
        capture.recordingType = body.opt("type")?.toString()?.toIntOrNull()
        capture.sampleRate = sampleRateFromAttributes(body.optString("attrs"))
        rememberFileAttributes(body, fid)
        emitEvent(A1DeviceEvent(
            kind = A1EventKind.STREAM_ATTRIBUTES,
            frameType = frameType,
            command = 0x0116,
            fid = fid,
            recordingType = capture.recordingType,
            streamType = capture.streamType,
            sampleRate = capture.sampleRate
        ))
    }

    private fun handleAudioPush(payload: ByteArray) {
        val parsed = parseAudioPush(payload) ?: return
        val (fid, blockSequence, packets) = parsed
        if (packets.isEmpty()) return
        val capture = currentCapture?.takeIf { it.fid == fid } ?: CaptureBuilder(fid).also {
            currentCapture = it
            activeRecordingFid = fid
        }
        val signature = "$blockSequence:${packets.sumOf { it.size }}:${packets.fold(1) { hash, packet -> 31 * hash + packet.contentHashCode() }}"
        if (!capture.seenBlocks.add(signature)) return
        capture.packets += packets
    }

    /** 0x0102 is AUDIO_REMARK_SEND. Static SDK evidence uses relative seconds in `ts`. */
    private fun handleRemark(frameType: Int, payload: ByteArray) {
        val body = parseJson(payload)?.eventBody() ?: return
        val fid = body.opt("fid")?.toString()?.toLongOrNull() ?: activeRecordingFid ?: return
        val relativeSeconds = body.opt("ts")?.toString()?.toIntOrNull() ?: return
        emitEvent(A1DeviceEvent(
            kind = A1EventKind.MARKER,
            frameType = frameType,
            command = 0x0102,
            fid = fid,
            relativeSeconds = relativeSeconds,
            markerType = body.opt("type")?.toString()?.toIntOrNull()
        ))
    }

    /**
     * 0x000C is a batched UT_REPORT, not a dedicated marker event. Each record is retained as
     * telemetry and deliberately left semantically unnamed until controlled samples agree.
     */
    private fun handleUtReport(frameType: Int, payload: ByteArray) {
        if (payload.size < 10 || payload[0] != 0x5A.toByte() || payload[1] != 0x5A.toByte()) return
        val values = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val recordBytes = values.getShort(2).toInt() and 0xffff
        if (recordBytes % 20 != 0 || payload.size != recordBytes + 10) return
        val countOffset = 4 + recordBytes
        if (payload[countOffset + 4] != 0x5A.toByte() || payload[countOffset + 5] != 0x5A.toByte()) return
        val declaredCount = values.getInt(countOffset).toLong() and 0xffffffffL
        if (declaredCount != (recordBytes / 20).toLong()) return

        repeat(declaredCount.toInt()) { index ->
            val offset = 4 + index * 20
            val sequence = values.getShort(offset).toInt() and 0xffff
            val timestamp = values.getInt(offset + 4).toLong() and 0xffffffffL
            val eventClass = payload[offset + 8].toInt() and 0xff
            val eventCode = payload[offset + 11].toInt() and 0xff
            val argument0 = values.getInt(offset + 12).toLong() and 0xffffffffL
            val argument1 = values.getInt(offset + 16).toLong() and 0xffffffffL
            val fid = activeRecordingFid
            // A controlled long-recording sample verified 0x0B/0x01 as the physical marker
            // event; argument1 is the user-visible relative second. UT batches can arrive later,
            // so the device event timestamp is retained only for diagnostics.
            val isVerifiedMarker = eventClass == 0x0B && eventCode == 0x01 && argument0 == 0L
            emitEvent(A1DeviceEvent(
                kind = if (isVerifiedMarker) A1EventKind.MARKER else A1EventKind.UT_REPORT,
                frameType = frameType,
                command = 0x000C,
                fid = fid,
                relativeSeconds = if (isVerifiedMarker) argument1.toInt()
                    else fid?.let { (timestamp - it).coerceAtLeast(0).toInt() },
                telemetrySequence = sequence,
                telemetryClass = eventClass,
                telemetryCode = eventCode,
                telemetryArgument0 = argument0,
                telemetryArgument1 = argument1,
                deviceTimestamp = timestamp
            ))
        }
    }

    private fun finishCapture(capture: CaptureBuilder) {
        if (capture.packets.isEmpty()) return
        val packets = capture.packets.map(ByteArray::clone)
        ioScope.launch {
            val result = runCatching {
                val target = File(context.filesDir, "audio/a1-live-${capture.fid}.ogg")
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
                try {
                    temporary.writeBytes(
                        DtyjOgg.wrapOpusPackets(packets, capture.sampleRate, capture.fid.toInt())
                    )
                    // Both files live in the same app directory, so rename is atomic and replaces
                    // any partial capture left by an earlier duplicate start/stop sequence.
                    Os.rename(temporary.absolutePath, target.absolutePath)
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
                A1LiveCapture(
                    fid = capture.fid,
                    localFile = target,
                    durationSeconds = packets.size * 0.02,
                    packetCount = packets.size,
                    sampleRate = capture.sampleRate,
                    recordingType = capture.recordingType,
                    streamType = capture.streamType
                )
            }
            result.getOrNull()?.let { _completedCaptures.emit(it) }
            result.exceptionOrNull()?.let { Log.w(TAG, "Unable to save A1 live capture", it) }
        }
    }

    private fun emitEvent(event: A1DeviceEvent) {
        _events.tryEmit(event)
        _recentEvents.value = (_recentEvents.value + event).takeLast(40)
    }

    private fun parseAudioPush(payload: ByteArray): Triple<Long, Int, List<ByteArray>>? {
        if (payload.size < 28) return null
        val data = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val fid = data.getInt(4).toLong() and 0xffffffffL
        val blockSequence = data.getInt(16)
        val audioLength = data.getInt(20)
        if (audioLength == 0) return Triple(fid, blockSequence, emptyList())
        if (audioLength < 0 || 28 + audioLength > payload.size || audioLength % 84 != 0) return null
        val packets = (0 until audioLength step 84).map { offset ->
            payload.copyOfRange(28 + offset, 28 + offset + 84)
        }
        return Triple(fid, blockSequence, packets)
    }

    private fun sampleRateFromAttributes(attrs: String): Int =
        attrs.split('@').getOrNull(2)?.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: 16_000

    private fun parseJson(payload: ByteArray): JSONObject? = runCatching {
        JSONObject(String(payload, Charsets.UTF_8))
    }.getOrNull()

    /** Native callbacks sometimes wrap the wire JSON in `body`; raw BLE samples do not. */
    private fun JSONObject.eventBody(): JSONObject = optJSONObject("body") ?: this

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(gatt)) {
                runCatching { gatt.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connectResult?.complete(Unit)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = false
                currentCapture = null
                activeRecordingFid = null
                failPending(IllegalStateException("A1 已断开 ($status)"))
                connectResult?.takeUnless { it.isCompleted }
                    ?.completeExceptionally(IllegalStateException("A1 已断开 ($status)"))
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt)) return
            mtuResult?.complete(Unit)
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) servicesResult?.complete(Unit)
            else servicesResult?.completeExceptionally(IllegalStateException("A1 服务发现失败：$status"))
        }
        @Deprecated("API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!isCurrentGatt(gatt)) return
            consume(characteristic.value ?: return)
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!isCurrentGatt(gatt)) return
            consume(value)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) operationResult?.complete(Unit)
            else operationResult?.completeExceptionally(IllegalStateException("A1 写入失败：$status"))
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrentGatt(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) operationResult?.complete(Unit)
            else operationResult?.completeExceptionally(IllegalStateException("A1 通知开启失败：$status"))
        }
    }

    private fun isCurrentGatt(callbackGatt: BluetoothGatt): Boolean = callbackGatt === gatt

    private fun closeFailedConnection(failedGatt: BluetoothGatt, cause: Throwable) {
        runCatching { failedGatt.disconnect() }
        runCatching { failedGatt.close() }
        if (gatt !== failedGatt) return
        gatt = null
        commandCharacteristic = null
        incoming = ByteArray(0)
        failPending(cause)
        currentCapture = null
        activeRecordingFid = null
        _connectionState.value = false
    }

    private fun requirePermissions() {
        if (Build.VERSION.SDK_INT >= 31) check(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { "请允许附近设备权限" }
        else check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "请允许定位权限以搜索 BLE" }
    }

    private fun makeToken(secret: String, challenge: String): String {
        val key = secret.take(16).toByteArray(Charsets.US_ASCII)
        val plain = challenge.toByteArray(Charsets.US_ASCII)
        require(plain.size % 16 == 0) { "A1 challenge 长度异常" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return cipher.doFinal(plain).joinToString("") { "%02x".format(it) }
    }

    private fun File.startsWith(magic: ByteArray): Boolean {
        if (length() < magic.size) return false
        return inputStream().use { input ->
            val prefix = ByteArray(magic.size)
            input.read(prefix) == magic.size && prefix.contentEquals(magic)
        }
    }

    private fun frame(type: Int, command: Int, sequence: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(type.toByte()).putShort(command.toShort()).put(sequence.toByte()).putInt(payload.size).put(payload).array()

    private fun findSize(value: Any?): Long? = when (value) {
        is JSONObject -> {
            listOf("size", "file_size", "fileSize", "total_size", "totalSize").firstNotNullOfOrNull { key ->
                value.opt(key)?.toString()?.toLongOrNull()
            } ?: value.keys().asSequence().mapNotNull { findSize(value.opt(it)) }.firstOrNull()
        }
        is org.json.JSONArray -> (0 until value.length()).asSequence().mapNotNull { findSize(value.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun rememberFileAttributes(value: JSONObject, fallbackFid: Long? = null) {
        val body = value.eventBody()
        val fid = body.optLongOrNull("fid") ?: fallbackFid ?: return
        val parsed = A1FileAttributes(
            fid = fid,
            codecAttributes = body.optStringOrNull("attrs"),
            fileVersion = body.optStringOrNull("file_ver"),
            recordingType = body.optIntOrNull("type"),
            streamType = body.optIntOrNull("stream_type"),
            fileSync = body.optBooleanOrNull("fsync"),
            aes = body.optIntOrNull("aes"),
            algorithmMode = body.optIntOrNull("alg_mode"),
            incognitoMode = body.optIntOrNull("incognitomode"),
            sid = body.optIntOrNull("sid"),
            sizeBytes = findSize(body),
            raw = body.toValueMap()
        )
        _fileAttributes.value = (_fileAttributes.value + (fid to parsed)).entries.toList()
            .takeLast(MAX_ATTRIBUTE_HISTORY)
            .associate { it.toPair() }
    }

    private fun parseStatus(value: JSONObject) = A1DeviceStatus(
        audioStatus = value.optStringOrNull("audio_status"),
        activeFid = value.optLongOrNull("fid"),
        durationSeconds = value.optIntOrNull("duration"),
        batteryPercent = value.optIntOrNull("battery_percent"),
        storageRemainingMb = value.optLongOrNull("storage_remain"),
        storageTotalMb = value.optLongOrNull("storage_total_size"),
        firmwareVersion = value.optStringOrNull("version"),
        batteryCasePercent = value.optIntOrNull("battery_percent_case"),
        batteryLeftPercent = value.optIntOrNull("battery_percent_left"),
        batteryRightPercent = value.optIntOrNull("battery_percent_right"),
        deviceRole = value.optIntOrNull("device_role"),
        peerOnline = value.optBooleanOrNull("peer_online"),
        storageLeftRemainingMb = value.optLongOrNull("storage_left_remain"),
        storageLeftTotalMb = value.optLongOrNull("storage_left_total"),
        storageRightRemainingMb = value.optLongOrNull("storage_right_remain"),
        storageRightTotalMb = value.optLongOrNull("storage_right_total"),
        vendorVersionLeft = value.optStringOrNull("vendor_version_left"),
        vendorVersionRight = value.optStringOrNull("vendor_version_right")
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()?.takeIf(String::isNotBlank)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()?.toIntOrNull()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()?.toLongOrNull()

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> when (raw?.toString()?.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }
    }

    private fun JSONObject.toIntMap(
        prefix: String? = null,
        exclude: Set<String> = emptySet()
    ): Map<String, Int> = keys().asSequence().mapNotNull { key ->
        if (key in exclude || (prefix != null && !key.startsWith(prefix))) return@mapNotNull null
        opt(key)?.toString()?.toIntOrNull()?.let { key to it }
    }.toMap()

    private fun JSONObject.toValueMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        opt(key).let { if (it == JSONObject.NULL) null else it }
    }

    companion object {
        private const val TAG = "DingTalkA1Client"
        private const val MAX_ATTRIBUTE_HISTORY = 100
        val SERVICE_UUID: UUID = UUID.fromString("0000fe3c-0000-1000-8000-00805f9b34fb")
        private val COMMAND_UUID = UUID.fromString("0000fe1c-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID = UUID.fromString("0000fe1b-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BABA_MAGIC = byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte())
        private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
