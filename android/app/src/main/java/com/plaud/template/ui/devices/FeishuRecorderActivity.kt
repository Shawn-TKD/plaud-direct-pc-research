package com.plaud.template.ui.devices

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.plaud.template.PlaudTemplateApp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plaud.template.R
import com.plaud.template.databinding.ActivityFeishuRecorderBinding
import com.plaud.template.hub.device.feishu.D3200Client
import com.plaud.template.hub.device.feishu.D3200DeviceInfo
import com.plaud.template.hub.device.feishu.D3200Recording
import com.plaud.template.models.RecordingFile
import com.plaud.template.storage.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeishuRecorderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFeishuRecorderBinding
    private lateinit var client: D3200Client
    private var recordings: List<D3200Recording> = emptyList()
    private var deviceInfo = D3200DeviceInfo()
    private var deviceName = "飞书录音豆 D3200"
    private var selectedIndex = -1
    private var connected = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) connectAndLoad()
        else showStatus("需要附近设备权限才能连接录音豆", true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeishuRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        client = (application as PlaudTemplateApp).peripheralSessionHub.d3200Client
        connected = client.connectionState.value

        binding.backButton.setOnClickListener { finish() }
        binding.connectButton.setOnClickListener { requestThenConnect() }
        binding.refreshButton.setOnClickListener { refresh() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.downloadButton.setOnClickListener { selected()?.let { download(listOf(it)) } }
        binding.downloadAllButton.setOnClickListener { download(recordings) }
        binding.deleteButton.setOnClickListener { selected()?.let(::confirmDelete) }
        binding.fileList.choiceMode = ListView.CHOICE_MODE_SINGLE
        binding.fileList.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            renderActions()
        }
        renderActions()
        observeConnectionState()
    }

    private fun requestThenConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionRequest.launch(permissions)
    }

    private fun connectAndLoad() {
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                if (client.connectionState.value) {
                    connected = true
                    loadFromDevice()
                    return@runCatching
                }
                showStatus("正在搜索飞书录音豆 D3200…")
                val devices = withContext(Dispatchers.IO) { client.scan() }
                val target = devices.firstOrNull() ?: error("附近没有发现 D3200；请唤醒录音豆，并关闭飞书或 soundcore App 后重试")
                deviceName = target.name
                showStatus("发现 $deviceName，正在建立本地 BLE 会话…")
                withContext(Dispatchers.IO) { client.connect(target.address) }
                connected = true
                loadFromDevice()
            }.onFailure {
                connected = client.connectionState.value
                showStatus(it.message ?: "连接失败", true)
            }
            setBusy(false)
            renderActions()
        }
    }

    private suspend fun loadFromDevice() {
        showStatus("已连接，正在读取设备与录音列表…")
        deviceInfo = withContext(Dispatchers.IO) { client.readDeviceInfo() }
        recordings = withContext(Dispatchers.IO) { client.listRecordings() }
        selectedIndex = if (recordings.isEmpty()) -1 else 0
        renderDeviceInfo()
        renderRecordings()
        showStatus("已连接 $deviceName · ${recordings.size} 条录音")
    }

    private fun refresh() {
        if (!connected) return requestThenConnect()
        setBusy(true)
        lifecycleScope.launch {
            runCatching { loadFromDevice() }
                .onFailure { showStatus(it.message ?: "刷新失败", true) }
            setBusy(false)
            renderActions()
        }
    }

    private fun toggleRecording() {
        if (!connected) return requestThenConnect()
        val starting = deviceInfo.recordStatus != 1
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (starting) client.startRecording() else client.pauseRecording()
                }
                deviceInfo = deviceInfo.copy(recordStatus = if (starting) 1 else 2)
                binding.recordButton.text = if (starting) "暂停录音" else "继续录音"
                showStatus(if (starting) "录音豆正在录音" else "录音已暂停")
            }.onFailure { showStatus(it.message ?: "录音控制失败", true) }
            setBusy(false)
            renderActions()
        }
    }

    private fun download(targets: List<D3200Recording>) {
        if (targets.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            var completed = 0
            targets.forEachIndexed { index, item ->
                runCatching {
                    showStatus("正在下载并解密 ${index + 1}/${targets.size}…")
                    val destination = File(filesDir, "audio/d3200-${item.fileId}.ogg")
                    withContext(Dispatchers.IO) { client.downloadRecording(item, destination) }
                    val serial = deviceInfo.serialNumber?.ifBlank { null } ?: "D3200"
                    RecordingStore.addFiles(listOf(RecordingFile(
                        sessionId = item.fileId,
                        deviceSN = "FEISHU_$serial",
                        name = "录音豆 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.fileId * 1000))}",
                        duration = item.estimatedDurationMs / 1000,
                        createdAt = item.fileId * 1000,
                        syncedAt = System.currentTimeMillis(),
                        localPath = destination.absolutePath
                    )))
                    completed++
                }.onFailure { showStatus("第 ${index + 1} 条下载失败：${it.message}", true) }
            }
            if (completed > 0) {
                showStatus("已下载并解密 $completed/${targets.size} 条，可在“录音”中播放、转录和总结")
                Toast.makeText(this@FeishuRecorderActivity, "录音已保存到本机", Toast.LENGTH_SHORT).show()
            }
            setBusy(false)
            renderActions()
        }
    }

    private fun confirmDelete(recording: D3200Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle("从录音豆删除？")
            .setMessage("删除后不可恢复。已下载到本机的副本不会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> delete(recording) }
            .show()
    }

    private fun delete(recording: D3200Recording) {
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.deleteRecording(recording.fileId) }
                recordings = withContext(Dispatchers.IO) { client.listRecordings() }
                selectedIndex = if (recordings.isEmpty()) -1 else 0
                renderRecordings()
                showStatus("录音已从设备删除；本机备份不受影响")
            }.onFailure { showStatus(it.message ?: "删除失败", true) }
            setBusy(false)
            renderActions()
        }
    }

    private fun renderDeviceInfo() {
        val used = if (deviceInfo.totalMemoryKb != null && deviceInfo.freeMemoryKb != null) {
            val totalGb = deviceInfo.totalMemoryKb!! / 1024.0 / 1024.0
            val freeGb = deviceInfo.freeMemoryKb!! / 1024.0 / 1024.0
            "%.2f / %.2f GB 可用".format(Locale.US, freeGb, totalGb)
        } else "—"
        binding.deviceInfo.text = buildString {
            append(deviceInfo.serialNumber ?: deviceName)
            append("\n录音豆电量：${deviceInfo.battery?.let { "$it%" } ?: "—"}")
            deviceInfo.boxBattery?.let { append(" · 充电仓：$it%") }
            append("\n固件：${deviceInfo.firmwareVersion ?: "—"} · 存储：$used")
        }
        binding.recordButton.text = if (deviceInfo.recordStatus == 1) "暂停录音" else "开始录音"
    }

    private fun renderRecordings() {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val labels = recordings.map { item ->
            val duration = item.estimatedDurationMs / 1000
            "${formatter.format(Date(item.fileId * 1000))}\n${duration / 60} 分 ${duration % 60} 秒 · ${formatSize(item.sizeBytes)}"
        }
        binding.fileList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, labels)
        if (selectedIndex in recordings.indices) binding.fileList.setItemChecked(selectedIndex, true)
        binding.emptyLabel.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        renderActions()
    }

    private fun selected(): D3200Recording? = recordings.getOrNull(selectedIndex)

    private fun renderActions() {
        val hasSelection = connected && selected() != null
        binding.refreshButton.isEnabled = connected
        binding.recordButton.isEnabled = connected
        binding.downloadButton.isEnabled = hasSelection
        binding.deleteButton.isEnabled = hasSelection
        binding.downloadAllButton.isEnabled = connected && recordings.isNotEmpty()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !busy
        if (busy) {
            binding.refreshButton.isEnabled = false
            binding.recordButton.isEnabled = false
            binding.downloadButton.isEnabled = false
            binding.downloadAllButton.isEnabled = false
            binding.deleteButton.isEnabled = false
        }
    }

    private fun showStatus(message: String, error: Boolean = false) {
        binding.statusLabel.text = message
        binding.statusDot.setBackgroundColor(getColor(if (error) R.color.red else R.color.green))
    }

    private fun formatSize(bytes: Long): String = if (bytes >= 1024 * 1024) {
        "%.2f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    } else "%.1f KB".format(Locale.US, bytes / 1024.0)

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.connectionState.collect { isConnected ->
                    val wasConnected = connected
                    connected = isConnected
                    binding.connectButton.text = if (isConnected) "读取设备" else "扫描并连接"
                    if (wasConnected && !isConnected) {
                        showStatus("录音豆连接已断开；点按重新连接", true)
                    }
                    renderActions()
                }
            }
        }
    }
}
