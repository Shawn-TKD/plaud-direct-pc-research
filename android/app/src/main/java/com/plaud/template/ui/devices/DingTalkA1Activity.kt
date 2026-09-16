package com.plaud.template.ui.devices

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.databinding.ActivityDingtalkA1Binding
import com.plaud.template.hub.device.A1TransferPhase
import com.plaud.template.hub.device.A1TransferStatus
import com.plaud.template.hub.device.PeripheralSessionHub
import com.plaud.template.hub.device.dingtalk.A1Credentials
import com.plaud.template.hub.device.dingtalk.A1Inspection
import com.plaud.template.hub.device.dingtalk.A1RemoteRecording
import com.plaud.template.hub.device.dingtalk.DingTalkA1Client
import com.plaud.template.hub.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DingTalkA1Activity : AppCompatActivity() {
    private lateinit var binding: ActivityDingtalkA1Binding
    private lateinit var peripheralHub: PeripheralSessionHub
    private lateinit var client: DingTalkA1Client
    private lateinit var vault: SecretVault
    private val prefs by lazy { getSharedPreferences("dingtalk_a1_config", MODE_PRIVATE) }
    private var credentials: A1Credentials? = null
    private var remoteFiles: List<A1RemoteRecording> = emptyList()
    private var selectedIndex = -1
    private var lastSuccessfulInspection: A1Inspection? = null
    private var connectedDeviceName = "钉钉 A1"
    private var connected = false
    private var busy = false
    private var speedFid: Long? = null
    private var speedStarted = 0L
    private var speedStartBytes = 0L
    private var credentialsExpanded = true
    private val wifiPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) binding.wifiTransferButton.performClick()
        else showStatus("需要附近 Wi-Fi / 位置权限连接 A1 热点", true)
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectAndLoad()
        else showStatus("需要附近设备权限才能连接 A1", true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDingtalkA1Binding.inflate(layoutInflater)
        setContentView(binding.root)
        peripheralHub = (application as PlaudTemplateApp).peripheralSessionHub
        client = peripheralHub.dingTalkA1Client
        connected = client.connectionState.value
        vault = SecretVault(this)

        binding.backButton.setOnClickListener { finish() }
        binding.usbInfoButton.setOnClickListener {
            val manager = getSystemService(android.hardware.usb.UsbManager::class.java)
            val devices = manager.deviceList.values.filter { it.vendorId == 0x17ef && it.productId == 0x0101 }
            val message = if (devices.isEmpty()) "未发现 USB A1。请使用支持数据的 OTG 线，将 A1 直接接到手机，而不是电脑。"
                else "发现 ${devices.size} 台 A1 USB 设备。\n" + devices.joinToString("\n") { "接口数：${it.interfaceCount} · VID 17EF / PID 0101" }
            MaterialAlertDialogBuilder(this).setTitle("USB 有线检测")
                .setMessage(message + "\n\n电脑端已验证 HID → ADB 文件访问；手机版尚未移植 ADB 文件传输。本按钮不切换模式、不影响录音。ADB 模式会断开蓝牙，不能与当前 BLE 会话同时使用。")
                .setPositiveButton("知道了", null).show()
        }
        binding.wifiTransferButton.setOnClickListener {
            if (busy) return@setOnClickListener
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                else Manifest.permission.ACCESS_FINE_LOCATION
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                wifiPermissionRequest.launch(arrayOf(permission))
                return@setOnClickListener
            }
            val c = readCredentials() ?: return@setOnClickListener
            if (!connected) { showStatus("请先连接 A1", true); return@setOnClickListener }
            MaterialAlertDialogBuilder(this).setTitle(if (client.wifiSessionActive) "关闭 A1 热点" else "开启实验性高速传输")
                .setMessage("请先停止录音。开启热点会取消 BLE 文件传输并增加耗电。批准系统的热点连接请求后，再点下载录音；不改变手机其他 App 的网络。手机端尚待实机验收，用完请关闭热点。")
                .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ ->
                    setBusy(true)
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (client.wifiSessionActive) client.closeAudioWifi(c) else client.openAudioWifi(c)
                            }
                            binding.wifiTransferButton.text = if (client.wifiReady) "关闭 Wi-Fi 高速" else "Wi-Fi 高速 · 实验"
                            showStatus(if (client.wifiReady) "Wi-Fi 已连接，下载按钮现在使用 HTTP 高速通道" else "热点已关闭，恢复 BLE 下载")
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { showStatus(error.message ?: "Wi-Fi 连接失败", true) }
                        finally { setBusy(false) }
                    }
                }.show()
        }
        val savedDid = prefs.getString("did", "").orEmpty()
        val savedCorp = prefs.getString("corp_id", "").orEmpty()
        val hasSavedSecret = vault.isConfigured(SecretVault.DINGTALK_DEVICE_SECRET)
        binding.didInput.setText(savedDid)
        binding.corpInput.setText(savedCorp)
        binding.secretInputLayout.placeholderText =
            if (hasSavedSecret) "已加密保存；留空沿用" else null
        binding.secretInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setCredentialsExpanded(!(savedDid.isNotBlank() && savedCorp.isNotBlank() && hasSavedSecret))

        binding.connectButton.setOnClickListener { requestThenConnect() }
        binding.credentialHeader.setOnClickListener {
            setCredentialsExpanded(!credentialsExpanded)
        }
        binding.refreshButton.setOnClickListener { refreshFiles() }
        binding.downloadButton.setOnClickListener { selected()?.let { download(listOf(it)) } }
        binding.downloadAllButton.setOnClickListener { download(remoteFiles) }
        binding.deleteButton.setOnClickListener { selected()?.let(::confirmDelete) }
        binding.fileList.choiceMode = ListView.CHOICE_MODE_SINGLE
        binding.fileList.setOnItemClickListener { _, _, position, _ ->
            selectedIndex = position
            renderActions()
        }
        renderDeviceSummary(null)
        renderConnectionUi()
        showStatus(if (connected) "A1 已在后台保持连接，点按同步设备录音" else "连接后即可同步设备录音")
        renderActions()
        observeConnectionState()
        observeTransfers()
        if (connected && savedDid.isNotBlank() && savedCorp.isNotBlank() && hasSavedSecret) {
            binding.root.post { if (!busy && !isFinishing) connectAndLoad() }
        }
    }

    private fun requestThenConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionRequest.launch(permissions)
    }

    private fun readCredentials(): A1Credentials? {
        val did = binding.didInput.text.toString().trim()
        val corp = binding.corpInput.text.toString().trim()
        val entered = binding.secretInput.text.toString().trim()
        val secret = entered.ifBlank {
            vault.get(SecretVault.DINGTALK_DEVICE_SECRET).orEmpty()
        }
        if (did.isBlank() || corp.isBlank() || secret.length < 16) {
            setCredentialsExpanded(true)
            showStatus("请填写 DID、corpId 和 deviceSecret（至少 16 位）", true)
            return null
        }
        return A1Credentials(did, corp, secret)
    }

    private fun connectAndLoad() {
        val c = readCredentials() ?: return
        credentials = c
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                if (client.connectionState.value) {
                    val knownAddress = peripheralHub.savedDingTalkAddress()
                        ?: error("缺少 A1 蓝牙地址，请断开后重新扫描")
                    withContext(Dispatchers.IO) {
                        peripheralHub.connectDingTalk(knownAddress, c)
                    }
                    connected = true
                    loadFiles()
                    binding.secretInput.text?.clear()
                    updateCredentialSummary(true)
                    setCredentialsExpanded(false)
                    return@runCatching
                }
                showStatus("正在搜索钉钉 A1…")
                val devices = withContext(Dispatchers.IO) { client.scan() }
                val savedAddress = peripheralHub.savedDingTalkAddress()
                val target = (if (savedAddress != null) devices.firstOrNull { it.address == savedAddress }
                    else devices.singleOrNull())
                    ?: error(if (savedAddress != null) "未发现已保存的 A1，请唤醒设备并靠近手机"
                        else "需要只唤醒一台 A1 后连接，避免连接错误设备")
                connectedDeviceName = target.name
                showStatus("发现 ${target.name}，正在离线鉴权…")
                withContext(Dispatchers.IO) {
                    peripheralHub.connectDingTalk(target.address, c)
                }
                connected = true
                loadFiles()
                binding.secretInput.text?.clear()
                updateCredentialSummary(true)
                setCredentialsExpanded(false)
            }.onFailure {
                connected = client.connectionState.value
                showStatus(it.message ?: "连接失败", true)
            }
            setBusy(false)
            renderActions()
        }
    }

    private suspend fun loadFiles() {
        val c = credentials ?: error("尚未配置 A1 凭据")
        showStatus("鉴权成功，正在读取设备状态与录音列表…")
        val inspection = try {
            withContext(Dispatchers.IO) { client.inspect(c) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (inspection != null) lastSuccessfulInspection = inspection
        remoteFiles = withContext(Dispatchers.IO) { peripheralHub.refreshDingTalkCatalog() }
        selectedIndex = -1
        renderDeviceSummary(inspection ?: lastSuccessfulInspection)
        renderFiles()
        showStatus(buildInspectionStatus(inspection))
    }

    private fun refreshFiles() {
        if (!connected) return requestThenConnect()
        setBusy(true)
        lifecycleScope.launch {
            runCatching { loadFiles() }
                .onFailure { showStatus(it.message ?: "刷新失败", true) }
            setBusy(false)
            renderActions()
        }
    }

    private fun renderFiles() {
        val labels = remoteFiles.map(::buildRecordingLabel)
        binding.fileList.adapter =
            ArrayAdapter(this, R.layout.item_a1_recording, R.id.recordingLabel, labels)
        binding.fileList.clearChoices()
        if (selectedIndex in remoteFiles.indices) binding.fileList.setItemChecked(selectedIndex, true)
        binding.fileCountLabel.text = "${remoteFiles.size} 条"
        binding.recordingCountValue.text = remoteFiles.size.toString()
        binding.emptyLabel.text = if (connected) {
            "A1 中暂时没有可同步的长录音\n短按语音备忘录会实时保存到 Files，不出现在设备长录音列表"
        } else {
            "连接设备并刷新后，长录音会显示在这里\n短按语音备忘录会实时保存到 Files，不出现在设备长录音列表"
        }
        binding.emptyLabel.visibility = if (remoteFiles.isEmpty()) View.VISIBLE else View.GONE
        renderActions()
    }

    private fun download(targets: List<A1RemoteRecording>) {
        if (credentials == null) return
        if (targets.isEmpty()) return
        setBusy(true)
        val importTask = peripheralHub.startDingTalkImport(targets.map { it.fid })
        lifecycleScope.launch {
            runCatching { importTask.await() }.onSuccess { result ->
                val completed = result.importedFids.size
                val failed = result.failures.size
                showStatus(
                    if (failed == 0) {
                        "已下载 $completed/${targets.size} 条，可在 Files 中播放、转录和总结"
                    } else {
                        "已下载 $completed 条，$failed 条失败；可重新选择后重试"
                    },
                    error = failed > 0 && completed == 0
                )
                if (completed > 0) {
                    Toast.makeText(this@DingTalkA1Activity, "录音已保存到 Files", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                showStatus(it.message ?: "下载失败", true)
            }
            if (peripheralHub.dingTalkTransfers.value.values.none {
                    it.phase == A1TransferPhase.DOWNLOADING
                }) {
                binding.transferProgressBar.visibility = View.GONE
            }
            setBusy(false)
            renderActions()
        }
    }

    private fun confirmDelete(recording: A1RemoteRecording) {
        MaterialAlertDialogBuilder(this)
            .setTitle("从钉钉 A1 删除？")
            .setMessage("删除后无法恢复。已经下载到本机 Files 的副本不会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> delete(recording) }
            .show()
    }

    private fun delete(recording: A1RemoteRecording) {
        if (credentials == null) return
        setBusy(true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    peripheralHub.deleteDingTalkRecording(recording.fid)
                }
                remoteFiles = peripheralHub.dingTalkCatalog.value
                selectedIndex = -1
                renderFiles()
                showStatus("录音已从 A1 删除；本机副本不受影响")
            }.onFailure { showStatus(it.message ?: "删除失败", true) }
            setBusy(false)
            renderActions()
        }
    }

    private fun selected(): A1RemoteRecording? = remoteFiles.getOrNull(selectedIndex)

    private fun renderActions() {
        val selected = selected()
        val activeTransfer = peripheralHub.dingTalkTransfers.value.values.any {
            it.phase == A1TransferPhase.DOWNLOADING
        }
        val canAct = connected && !busy && !activeTransfer
        val hasSelection = canAct && selected != null
        binding.refreshButton.isEnabled = canAct
        binding.downloadButton.isEnabled = hasSelection
        binding.deleteButton.isEnabled = hasSelection
        binding.downloadAllButton.isEnabled = canAct && remoteFiles.isNotEmpty()
        binding.actionsCard.visibility =
            if (!credentialsExpanded && selected != null) View.VISIBLE else View.GONE
        binding.selectedFileLabel.text = selected?.let {
            val date = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(it.fid * 1000))
            "已选择 · $date · ${formatDuration(it.durationSeconds)}"
        } ?: "选择一条设备录音"
    }

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connectButton.isEnabled = !busy
        renderConnectionUi()
        renderActions()
    }

    private fun showStatus(message: String, error: Boolean = false) {
        binding.statusLabel.text = message
        binding.statusDot.setBackgroundResource(
            when {
                error -> R.drawable.bg_status_dot_red
                connected -> R.drawable.bg_status_dot
                else -> R.drawable.bg_status_dot_gray
            }
        )
    }

    private fun formatDuration(seconds: Int): String =
        if (seconds >= 3600) "%d 小时 %d 分".format(seconds / 3600, seconds % 3600 / 60)
        else "%d 分 %d 秒".format(seconds / 60, seconds % 60)

    private fun buildInspectionStatus(inspection: A1Inspection?): String {
        if (inspection == null) {
            return "已连接 $connectedDeviceName，读取到 ${remoteFiles.size} 条录音；设备详情暂未返回"
        }
        val status = inspection.status
        val state = when (status.audioStatus) {
            "recording" -> "录音中"
            "paused" -> "已暂停"
            "idle" -> "空闲"
            else -> status.audioStatus
        }
        return "同步完成 · ${remoteFiles.size} 条设备录音${state?.let { " · $it" }.orEmpty()}"
    }

    private fun buildRecordingLabel(item: A1RemoteRecording): CharSequence {
        val date = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
            .format(Date(item.fid * 1000))
        val title = "长录音 · $date"
        val meta = "${formatDuration(item.durationSeconds)} · 保存在 A1 中"
        return SpannableStringBuilder().apply {
            append(title)
            append('\n')
            val metaStart = length
            append(meta)
            setSpan(
                ForegroundColorSpan(getColor(R.color.text_secondary)),
                metaStart,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                RelativeSizeSpan(0.9f),
                metaStart,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun renderDeviceSummary(inspection: A1Inspection?) {
        binding.deviceNameLabel.text = connectedDeviceName
        binding.recordingCountValue.text = if (connected || remoteFiles.isNotEmpty()) {
            remoteFiles.size.toString()
        } else {
            "—"
        }
        val status = inspection?.status
        binding.batteryValue.text = status?.batteryPercent?.let { "$it%" } ?: "—"
        binding.storageValue.text = status?.storageRemainingMb?.let {
            "%.1f GB".format(Locale.CHINA, it / 1024.0)
        } ?: "—"
        val capabilities = inspection?.let {
            buildList {
                if ((it.capabilities["cap_remark"] ?: 0) >= 2) add("标记")
                if ((it.capabilities["cap_voiceprint"] ?: 0) >= 1) add("声纹")
                if ((it.capabilities["cap_aikey_option"] ?: 0) >= 1) add("AI 按键")
                if ((it.capabilities["cap_incognitomode"] ?: 0) >= 1) add("隐身录音")
                if ((it.capabilities["cap_schedule"] ?: 0) >= 2) add("定时录音")
            }
        }.orEmpty()
        binding.deviceDetailLabel.text = buildString {
            append("固件 ${status?.firmwareVersion ?: "—"}")
            status?.storageTotalMb?.let {
                append(" · 总容量 %.1f GB".format(Locale.CHINA, it / 1024.0))
            }
            if (capabilities.isNotEmpty()) append(" · 支持 ${capabilities.joinToString("、")}")
        }
        renderConnectionUi()
    }

    private fun renderConnectionUi() {
        binding.deviceSubtitleLabel.text = when {
            busy && !connected -> "正在建立本地 BLE 连接"
            busy -> "正在读取设备录音"
            connected -> "本地 BLE 已连接 · 后台保持"
            else -> "本地 BLE 连接"
        }
        binding.connectionBadge.text = when {
            busy -> "处理中"
            connected -> "已连接"
            else -> "未连接"
        }
        binding.connectionBadge.setBackgroundResource(
            if (connected) R.drawable.bg_status_synced else R.drawable.bg_status_pending
        )
        binding.connectionBadge.setTextColor(getColor(R.color.text_primary))
        binding.connectButton.text = when {
            busy -> "请稍候…"
            connected -> "同步设备录音"
            else -> "扫描并连接"
        }
    }

    private fun setCredentialsExpanded(expanded: Boolean) {
        credentialsExpanded = expanded
        binding.credentialContent.visibility = if (expanded) View.VISIBLE else View.GONE
        val recordingVisibility = if (expanded) View.GONE else View.VISIBLE
        binding.statusCard.visibility = View.VISIBLE
        binding.recordingsHeader.visibility = recordingVisibility
        binding.recordingsListContainer.visibility = recordingVisibility
        binding.actionsCard.visibility =
            if (!expanded && selected() != null) View.VISIBLE else View.GONE
        updateCredentialSummary(vault.isConfigured(SecretVault.DINGTALK_DEVICE_SECRET))
        binding.credentialToggle.text = when {
            expanded -> "收起"
            vault.isConfigured(SecretVault.DINGTALK_DEVICE_SECRET) -> "修改"
            else -> "配置"
        }
    }

    private fun updateCredentialSummary(hasSavedSecret: Boolean) {
        val hasIdentity = binding.didInput.text?.isNotBlank() == true &&
            binding.corpInput.text?.isNotBlank() == true
        binding.credentialSummary.text = if (hasIdentity && hasSavedSecret) {
            "离线凭据已加密保存在本机"
        } else {
            "首次连接需要 DID、corpId 和 deviceSecret"
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.connectionState.collect { isConnected ->
                    val wasConnected = connected
                    connected = isConnected
                    renderConnectionUi()
                    if (wasConnected && !isConnected) {
                        showStatus("A1 连接已断开；点按重新连接", true)
                    } else if (!wasConnected && isConnected && !busy && credentials == null) {
                        connectAndLoad()
                    }
                    renderActions()
                }
            }
        }
    }

    private fun observeTransfers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                peripheralHub.dingTalkTransfers.collect(::renderTransferStatus)
            }
        }
    }

    private fun renderTransferStatus(transfers: Map<Long, A1TransferStatus>) {
        val active = transfers.values.firstOrNull { it.phase == A1TransferPhase.DOWNLOADING }
        if (active == null) {
            speedFid = null
            binding.transferProgressBar.visibility = View.GONE
            renderActions()
            return
        }
        val total = active.totalBytes
        val now = android.os.SystemClock.elapsedRealtime()
        if (speedFid != active.fid || active.receivedBytes < speedStartBytes) {
            speedFid = active.fid
            speedStarted = now
            speedStartBytes = active.receivedBytes
        }
        val elapsed = now - speedStarted
        val bytesPerSecond = if (elapsed >= 1000) (active.receivedBytes - speedStartBytes) * 1000 / elapsed else null
        val percent = total?.takeIf { it > 0 }
            ?.let { ((active.receivedBytes * 100) / it).toInt().coerceIn(0, 100) }
        binding.transferProgressBar.visibility = if (percent == null) View.GONE else View.VISIBLE
        percent?.let { binding.transferProgressBar.progress = it }
        showStatus(
            buildString {
                append("正在下载录音")
                if (percent != null) append(" · $percent%")
                append(" · ${formatBytes(active.receivedBytes)}")
                total?.let { append(" / ${formatBytes(it)}") }
                bytesPerSecond?.let { append(" · ${formatBytes(it)}/s（平均）") }
            }
        )
        renderActions()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.CHINA, bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(Locale.CHINA, bytes / 1024.0)
        else -> "$bytes B"
    }
}
