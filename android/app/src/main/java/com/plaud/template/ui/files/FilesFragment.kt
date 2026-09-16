package com.plaud.template.ui.files

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.common.WifiTransferPermission
import com.plaud.template.databinding.FragmentFilesBinding
import com.plaud.template.hub.device.A1TransferPhase
import com.plaud.template.hub.integrations.AiBatchState
import com.plaud.template.models.DeviceConnectionState
import com.plaud.template.models.RecordingFile
import com.plaud.template.models.SyncProgress
import com.plaud.template.models.SyncState
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.devices.DingTalkA1Activity
import com.plaud.template.ui.filedetail.FileDetailActivity
import com.plaud.template.ui.settings.IntegrationSettingsActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Files Tab — File list grouped by date + Sync Banner
 */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudTemplateApp
    private val syncManager get() = app.syncManager
    private val deviceManager get() = app.deviceManager
    private val peripheralHub get() = app.peripheralSessionHub

    private val adapter = FilesAdapter(::handleFileTapped)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.filesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.filesRecyclerView.adapter = adapter
        binding.filesRecyclerView.itemAnimator = null

        binding.searchButton.setOnClickListener {
            (activity as? com.plaud.template.ui.main.MainActivity)?.openAsk()
        }
        binding.refreshButton.setOnClickListener {
            refreshConnectedDeviceCatalogs()
        }
        binding.batchAiButton.setOnClickListener { showBatchAiDialog() }

        binding.syncBanner.fastTransferButton.setOnClickListener { startFastTransfer() }
        binding.fastTransferCard.setOnClickListener { showImportOptions() }

        observeManagers()
    }

    private fun observeManagers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    syncManager.files.collect { files ->
                        renderFiles(files)
                    }
                }

                launch {
                    app.aiProcessingManager.batchState.collect(::renderAiBatchState)
                }

                launch {
                    combine(
                        deviceManager.connectionState,
                        syncManager.state,
                        syncManager.files,
                        peripheralHub.dingTalkA1Client.connectionState
                    ) { connection, sync, files, a1Connected ->
                        DeviceAndSyncState(connection, sync, files, a1Connected)
                    }.collect { snapshot ->
                        val connection = snapshot.plaudConnection
                        val sync = snapshot.sync
                        val files = snapshot.files
                        updateSyncBanner(sync)
                        val available = (connection is DeviceConnectionState.Connected || snapshot.a1Connected) &&
                            !sync.isActive
                        val pendingCount = files.count { !it.isSynced && isPlaudFile(it) }
                        binding.refreshButton.isEnabled = available
                        binding.refreshButton.alpha = if (available) 1f else 0.35f
                        binding.fastTransferCard.isEnabled = !sync.isActive
                        binding.fastTransferCard.alpha = if (connection is DeviceConnectionState.Connected || sync.isActive) 1f else 0.72f
                        binding.fastTransferTitle.text = "从 PLAUD 导入录音"
                        binding.fastTransferActionLabel.visibility = if (sync.isActive) View.GONE else View.VISIBLE
                        binding.fastTransferActionLabel.text = if (pendingCount > 0) "导入 $pendingCount 条" else "检查"
                        binding.fastTransferSubtitle.text = when (sync) {
                            is SyncState.Syncing -> sync.progress.let { progress ->
                                val count = if (progress.totalFiles > 0) "${progress.syncedFiles}/${progress.totalFiles}" else ""
                                "正在通过蓝牙导入 $count"
                            }
                            is SyncState.WiFiConnecting -> "正在建立 NotePin 高速连接…"
                            is SyncState.WiFiTransferring -> sync.progress.transferDetailText
                                .ifBlank { "正在通过 Wi-Fi 传输…" }
                            is SyncState.Failed -> "导入失败：${sync.message} · 点击重试"
                            else -> if (connection is DeviceConnectionState.Connected) {
                                if (pendingCount > 0) "发现 $pendingCount 条设备录音 · 点击选择传输方式"
                                else "已连接 · 点击检查设备中的新录音"
                            } else {
                                "唤醒并连接 NotePin 后即可导入"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleFileTapped(file: RecordingFile) {
        if (!file.isSynced && isPlaudFile(file)) {
            showImportOptions()
            return
        }
        if (!file.isSynced && isDingTalkFile(file)) {
            importDingTalkRecording(file)
            return
        }
        val intent = Intent(requireContext(), FileDetailActivity::class.java)
        intent.putExtra("file_id", file.id)
        startActivity(intent)
    }

    private fun showBatchAiDialog() {
        val manager = app.aiProcessingManager
        if (manager.batchState.value is AiBatchState.Running) {
            Toast.makeText(requireContext(), "批量处理正在进行", Toast.LENGTH_SHORT).show()
            return
        }
        if (!manager.isSiliconFlowReady()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("需要硅基流动 API Key")
                .setMessage("请先在“设置 → AI 与自动化”中完成转录服务配置。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(requireContext(), IntegrationSettingsActivity::class.java))
                }
                .show()
            return
        }

        val files = RecordingStore.allFiles
        val localFiles = files.filter { file ->
            file.localPath?.let { File(it).isFile } == true
        }
        val candidates = localFiles.filter {
            it.transcriptJSON.isNullOrBlank() || it.summaryText.isNullOrBlank()
        }
        if (candidates.isEmpty()) {
            val message = if (localFiles.isEmpty()) {
                "还没有下载到手机的录音。请先完成设备同步。"
            } else {
                "所有本地录音都已经有转录和总结。"
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("无需批量处理")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val needsTranscription = candidates.count { it.transcriptJSON.isNullOrBlank() }
        val summaryOnly = candidates.size - needsTranscription
        val completed = localFiles.size - candidates.size
        val message = buildString {
            append("将按顺序处理 ${candidates.size} 条本地录音。\n\n")
            append("需要转录与总结：$needsTranscription 条\n")
            append("只需补充总结：$summaryOnly 条\n")
            append("已有完整结果并跳过：$completed 条\n\n")
            append("长录音会自动分段；处理中会产生相应 API 费用。单条失败不会中断其余任务。")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("全部生成转录与总结")
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("开始处理") { _, _ ->
                if (!manager.startBatch(candidates)) {
                    Toast.makeText(requireContext(), "已有 AI 任务正在运行", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun renderAiBatchState(state: AiBatchState) {
        when (state) {
            AiBatchState.Idle -> {
                binding.aiBatchBanner.visibility = View.GONE
                binding.batchAiButton.isEnabled = true
                binding.batchAiButton.text = "AI 全部"
            }
            is AiBatchState.Running -> {
                binding.aiBatchBanner.visibility = View.VISIBLE
                binding.aiBatchTitle.text = "正在批量生成 AI 洞察 · ${state.current}/${state.total}"
                binding.aiBatchStatus.text = "${state.fileName}\n${state.status}"
                binding.aiBatchProgress.progress =
                    (((state.current - 1).coerceAtLeast(0) * 100f) / state.total).toInt()
                binding.batchAiButton.isEnabled = false
                binding.batchAiButton.text = "${state.current}/${state.total}"
            }
            is AiBatchState.Completed -> {
                binding.aiBatchBanner.visibility = View.GONE
                binding.batchAiButton.isEnabled = true
                binding.batchAiButton.text = "AI 全部"
                syncManager.refreshFromStore()
                val failed = state.failures.size
                val details = if (failed == 0) {
                    "${state.succeeded} 条录音已全部生成转录和总结。"
                } else {
                    val examples = state.failures.take(3).joinToString("\n") {
                        "• ${it.fileName}：${it.message}"
                    }
                    "成功 ${state.succeeded} 条，失败 $failed 条。\n\n$examples"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(if (failed == 0) "批量处理完成" else "批量处理已结束")
                    .setMessage(details)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                app.aiProcessingManager.consumeBatchTerminal()
            }
        }
    }

    private fun isPlaudFile(file: RecordingFile): Boolean =
        !file.deviceSN.startsWith("DINGTALK") && !file.deviceSN.startsWith("FEISHU")

    private fun isDingTalkFile(file: RecordingFile): Boolean =
        file.deviceSN.startsWith("DINGTALK")

    private fun importDingTalkRecording(file: RecordingFile) {
        if (!peripheralHub.dingTalkA1Client.connectionState.value) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("钉钉 A1 尚未连接")
                .setMessage("先连接 A1，随后可直接从此列表导入、播放、转录和总结。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("去连接") { _, _ ->
                    startActivity(Intent(requireContext(), DingTalkA1Activity::class.java))
                }
                .show()
            return
        }
        if (peripheralHub.dingTalkTransfers.value[file.sessionId]?.phase == A1TransferPhase.DOWNLOADING) {
            Toast.makeText(requireContext(), "这条 A1 录音正在导入", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "开始从钉钉 A1 导入", Toast.LENGTH_SHORT).show()
        val importTask = peripheralHub.startDingTalkImport(listOf(file.sessionId))
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                importTask.await()
            }.onSuccess { result ->
                syncManager.refreshFromStore()
                if (result.importedFids.contains(file.sessionId)) {
                    val local = RecordingStore.allFiles.firstOrNull {
                        it.deviceSN == file.deviceSN && it.sessionId == file.sessionId && it.isSynced
                    }
                    if (local != null && isAdded) {
                        Toast.makeText(requireContext(), "A1 录音已导入", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(requireContext(), FileDetailActivity::class.java).apply {
                            putExtra("file_id", local.id)
                        })
                    }
                } else {
                    val message = result.failures[file.sessionId] ?: "导入失败"
                    if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "A1 导入失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun refreshConnectedDeviceCatalogs() {
        if (syncManager.state.value.isActive) {
            Toast.makeText(requireContext(), "录音正在导入，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val plaudConnected = deviceManager.connectionState.value is DeviceConnectionState.Connected
        val a1Connected = peripheralHub.dingTalkA1Client.connectionState.value
        if (!plaudConnected && !a1Connected) {
            Toast.makeText(requireContext(), "请先连接录音设备", Toast.LENGTH_SHORT).show()
            return
        }
        if (plaudConnected) syncManager.fetchFileList()
        if (a1Connected) {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { peripheralHub.refreshDingTalkCatalog() }
                    .onFailure { error ->
                        if (isAdded) Toast.makeText(
                            requireContext(),
                            error.message ?: "A1 目录刷新失败",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
        val source = when {
            plaudConnected && a1Connected -> "PLAUD 与钉钉 A1"
            a1Connected -> "钉钉 A1"
            else -> "PLAUD"
        }
        Toast.makeText(requireContext(), "正在刷新 $source 录音", Toast.LENGTH_SHORT).show()
    }

    private fun showImportOptions() {
        if (deviceManager.connectionState.value !is DeviceConnectionState.Connected) {
            Toast.makeText(requireContext(), "请先唤醒并连接 PLAUD", Toast.LENGTH_SHORT).show()
            return
        }
        if (syncManager.state.value.isActive) return

        val pendingCount = syncManager.files.value.count { !it.isSynced && isPlaudFile(it) }
        val message = if (pendingCount > 0) {
            "设备中有 $pendingCount 条待导入录音。蓝牙更稳定且无需切换网络；Wi-Fi 更适合较长录音。"
        } else {
            "App 将先重新检查设备目录，发现新录音后立即导入。"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (pendingCount > 0) "导入 $pendingCount 条录音" else "检查并导入录音")
            .setMessage(message)
            .setPositiveButton("开始蓝牙导入") { _, _ -> syncManager.startSync() }
            .setNeutralButton("Wi-Fi 高速导入") { _, _ -> startFastTransfer() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startFastTransfer() {
        if (RecordingStore.fastTransferNeverShowAgain &&
            WifiTransferPermission.isGranted(requireContext())) {
            syncManager.startWiFiTransfer()
        } else {
            com.plaud.template.ui.home.FastTransferSheet()
                .show(childFragmentManager, "FastTransferSheet")
        }
    }

    override fun onResume() {
        super.onResume()
        // DingTalk A1 and D3200 write directly to the shared store, independently of the PLAUD
        // SyncManager flow. Refresh here so a completed vendor download appears immediately.
        if (_binding != null) {
            syncManager.refreshFromStore()
            renderFiles(RecordingStore.allFiles)
            if (deviceManager.connectionState.value is DeviceConnectionState.Connected &&
                !syncManager.state.value.isActive) {
                syncManager.fetchFileList()
            }
            val a1TransferActive = peripheralHub.dingTalkTransfers.value.values
                .any { it.phase == A1TransferPhase.DOWNLOADING }
            if (peripheralHub.dingTalkA1Client.connectionState.value && !a1TransferActive) {
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { peripheralHub.refreshDingTalkCatalog() }
                }
            }
        }
    }

    private fun renderFiles(files: List<RecordingFile>) {
        adapter.submitFiles(files)
        binding.emptyLabel.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.filesRecyclerView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSyncBanner(state: SyncState) {
        val bannerRoot = binding.syncBanner.root
        when (state) {
            is SyncState.Syncing -> {
                fadeInBanner(bannerRoot)
                updateBannerContent(state.progress, false)
            }
            is SyncState.WiFiTransferring -> {
                fadeInBanner(bannerRoot)
                updateBannerContent(state.progress, true)
            }
            // Keep the banner untouched during the WiFi connect window (mirrors iOS)
            is SyncState.WiFiConnecting -> {}
            is SyncState.Completed -> {
                bannerRoot.postDelayed({ fadeOutBanner(bannerRoot) }, 2000)
            }
            else -> fadeOutBanner(bannerRoot)
        }
    }

    // Sync callbacks can switch state quickly. Atomic visibility avoids stale animation end-actions
    // hiding a banner that has already been shown again.
    private fun fadeInBanner(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        v.visibility = View.VISIBLE
    }

    private fun fadeOutBanner(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        v.visibility = View.GONE
    }

    private fun updateBannerContent(progress: SyncProgress, isWiFi: Boolean) {
        val banner = binding.syncBanner
        val isDingTalkA1 = progress.currentFileName?.startsWith("A1 ") == true
        banner.syncTitleLabel.text = when {
            isDingTalkA1 -> "正在导入钉钉 A1 录音"
            isWiFi -> getString(R.string.fast_transfer)
            else -> getString(R.string.syncing_recordings)
        }
        banner.syncCountLabel.text =
            if (progress.totalFiles > 0) "${progress.syncedFiles}/${progress.totalFiles}" else ""

        val fill = banner.syncProgressFill
        fill.post {
            val parent = fill.parent as? View ?: return@post
            val fraction = progress.progressFraction.coerceIn(0f, 1f)
            fill.layoutParams = fill.layoutParams.apply {
                width = (parent.width * fraction).toInt().coerceAtLeast(1)
            }
        }

        // Always show progress as a percentage so the readout never flickers between a speed and
        // a (usually "Untitled") file name; append the real transfer speed when one is reported.
        val percent = (progress.progressFraction.coerceIn(0f, 1f) * 100).toInt()
        banner.syncSpeedLabel.text = when {
            progress.totalFiles == 0 -> getString(R.string.retrieving_file_list)
            progress.transferDetailText.isNotBlank() -> "$percent% · ${progress.transferDetailText}"
            else -> "$percent%"
        }

        banner.fastTransferButton.visibility =
            if (isWiFi || isDingTalkA1) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class DeviceAndSyncState(
    val plaudConnection: DeviceConnectionState,
    val sync: SyncState,
    val files: List<RecordingFile>,
    val a1Connected: Boolean
)

// MARK: - RecyclerView Adapter

/**
 * File list adapter, supports date group headers
 */
class FilesAdapter(
    private val onFileTapped: (RecordingFile) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FILE = 1
    }

    private sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class FileItem(val file: RecordingFile) : ListItem()
    }

    private var items = listOf<ListItem>()

    fun submitFiles(files: List<RecordingFile>) {
        val sorted = files.sortedByDescending { it.createdAt }
        val grouped = mutableListOf<ListItem>()
        var lastDateKey = ""
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

        for (file in sorted) {
            val fileDate = Date(file.createdAt)
            val dateKey = dayFormat.format(fileDate)
            if (dateKey != lastDateKey) {
                lastDateKey = dateKey
                val cal = Calendar.getInstance().apply { time = fileDate }
                val title = when {
                    dayFormat.format(today.time) == dateKey -> "Today"
                    dayFormat.format(yesterday.time) == dateKey -> "Yesterday"
                    else -> dateFormat.format(fileDate)
                }
                grouped.add(ListItem.Header(title))
            }
            grouped.add(ListItem.FileItem(file))
        }
        val oldItems = items
        val newItems = grouped.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = newItems[newItemPosition]
                return when {
                    old is ListItem.Header && new is ListItem.Header -> old.title == new.title
                    old is ListItem.FileItem && new is ListItem.FileItem -> old.file.id == new.file.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> TYPE_HEADER
        is ListItem.FileItem -> TYPE_FILE
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            else -> FileViewHolder(inflater.inflate(R.layout.item_file_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item.title, position == 0)
            is ListItem.FileItem -> (holder as FileViewHolder).bind(item.file)
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(title: String, isFirst: Boolean) {
            val label = itemView.findViewById<TextView>(R.id.dateHeaderLabel)
            label.text = title
            // Match iOS: 40dp gap before each group, none before the first.
            val density = itemView.resources.displayMetrics.density
            val topPad = if (isFirst) 0 else (40 * density).toInt()
            label.setPadding(label.paddingLeft, topPad, label.paddingRight, label.paddingBottom)
        }
    }

    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(file: RecordingFile) {
            itemView.findViewById<TextView>(R.id.fileNameLabel).text = file.name
            itemView.findViewById<TextView>(R.id.fileMetaLabel).text = formatMeta(file)
            itemView.setOnClickListener { onFileTapped(file) }
        }

        private fun formatMeta(file: RecordingFile): String {
            val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val date = Date(file.createdAt)
            val dur = formatDuration(file.duration)
            val source = when {
                file.deviceSN.startsWith("DINGTALK") -> "钉钉 A1"
                file.deviceSN.startsWith("FEISHU") -> "飞书录音豆"
                else -> "PLAUD"
            }
            val location = if (file.isSynced) "已下载" else "设备内"
            return "$source · $location  ·  ${dateFormat.format(date)} ${timeFormat.format(date)}  ·  $dur"
        }

        private fun formatDuration(seconds: Long): String {
            if (seconds <= 0) return "--"
            val total = seconds.toInt()
            return if (total >= 3600) {
                String.format("%dh %dm", total / 3600, (total % 3600) / 60)
            } else {
                String.format("%dm %ds", total / 60, total % 60)
            }
        }
    }
}
