package com.plaud.template.ui.filedetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.plaud.template.hub.integrations.AiIntegrations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.databinding.ActivityFileDetailBinding
import com.plaud.template.hub.integrations.AiProcessingState
import com.plaud.template.models.DeviceConnectionState
import com.plaud.template.models.RecordingFile
import com.plaud.template.managers.DeviceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * File detail page
 * Header, in-place transcript/summary tabs, audio player and file actions.
 */
class FileDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileDetailBinding
    private val syncManager get() = (application as PlaudTemplateApp).syncManager
    private val deviceManager get() = (application as PlaudTemplateApp).deviceManager
    private val aiProcessingManager get() = (application as PlaudTemplateApp).aiProcessingManager

    private var currentFile: RecordingFile? = null
    private var selectedInsightsPage = INSIGHTS_TRANSCRIPT

    // Audio player (media3 ExoPlayer)
    private var preparedPath: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.moreButton.setOnClickListener { showMoreMenu(it) }
        setupAudioPlayerControls()

        val fileId = intent.getStringExtra("file_id") ?: run { finish(); return }
        loadFile(fileId)
        observeAiProcessing(fileId)
    }

    override fun onResume() {
        super.onResume()
        // Refresh data (after returning from a rename)
        currentFile?.let { loadFile(it.id) }
    }

    private fun loadFile(fileId: String) {
        // Read from the persistent store FIRST: updateTranscript/updateDuration write to disk,
        // while syncManager.files is an in-memory snapshot that may still hold stale objects
        // (e.g. transcriptJSON = null right after a transcription completes).
        val file = com.plaud.template.storage.RecordingStore.allFiles.find { it.id == fileId }
            ?: syncManager.files.value.find { it.id == fileId }
            ?: run { finish(); return }
        currentFile = file
        bindFile(file)
    }

    /** Plain-text transcript for the "Copy Transcript" menu action (set when parsing succeeds). */
    private var transcriptPlainText: String? = null

    private fun bindFile(file: RecordingFile) {
        binding.fileNameLabel.text = file.name

        // Meta line "MMM d, yyyy \u00B7 HH:mm \u00B7 Xm Ys" (mirrors iOS)
        val dateFormat = SimpleDateFormat("MMM d, yyyy \u00B7 HH:mm", Locale.getDefault())
        binding.fileDateLabel.text =
            "${dateFormat.format(Date(file.createdAt))} \u00B7 ${formatMetaDuration(file.duration)}"

        // Self-heal: entries synced before the duration fix have duration 0 stored \u2014 recompute
        // from the local audio and backfill so old files show the real length too.
        val localPath = file.localPath
        if (file.duration <= 0 && localPath != null && File(localPath).exists()) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val d = com.plaud.template.managers.SyncManager.shared.audioDurationSec(localPath)
                if (d > 0) {
                    com.plaud.template.storage.RecordingStore.updateDuration(file.id, d)
                    runOnUiThread {
                        binding.fileDateLabel.text =
                            "${dateFormat.format(Date(file.createdAt))} \u00B7 ${formatMetaDuration(d)}"
                    }
                }
            }
        }

        // Status badge (Android extra, kept by product decision)
        if (file.isSynced) {
            binding.statusBadge.text = getString(R.string.synced)
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.green))
            binding.statusBadge.setBackgroundResource(R.drawable.bg_status_synced)
        } else {
            binding.statusBadge.text = getString(R.string.pending)
            binding.statusBadge.setTextColor(ContextCompat.getColor(this, R.color.orange))
            binding.statusBadge.setBackgroundResource(R.drawable.bg_status_pending)
        }

        val hasSummary = !file.summaryText.isNullOrBlank()
        transcriptPlainText = com.plaud.template.common.TranscriptText.formatted(file.transcriptJSON)
            .takeIf(String::isNotBlank)
        val hasTranscript = transcriptPlainText != null
        val hasInsights = hasSummary || hasTranscript

        binding.insightsSection.visibility = if (hasInsights) View.VISIBLE else View.GONE
        binding.transcriptTab.isEnabled = hasTranscript
        binding.transcriptTab.alpha = if (hasTranscript) 1f else 0.35f
        binding.summaryTab.isEnabled = hasSummary
        binding.summaryTab.alpha = if (hasSummary) 1f else 0.35f
        if (selectedInsightsPage == INSIGHTS_TRANSCRIPT && !hasTranscript) {
            selectedInsightsPage = INSIGHTS_SUMMARY
        }
        if (selectedInsightsPage == INSIGHTS_SUMMARY && !hasSummary) {
            selectedInsightsPage = INSIGHTS_TRANSCRIPT
        }
        binding.transcriptTab.setOnClickListener {
            selectedInsightsPage = INSIGHTS_TRANSCRIPT
            currentFile?.let(::renderInsightsPage)
        }
        binding.summaryTab.setOnClickListener {
            selectedInsightsPage = INSIGHTS_SUMMARY
            currentFile?.let(::renderInsightsPage)
        }
        if (hasInsights) {
            renderInsightsPage(file)
        }

        val activeProcessing = aiProcessingManager.state.value as? AiProcessingState.Running
        val processingThisFile = activeProcessing?.fileId == file.id
        binding.continueAiButton.visibility =
            if (hasTranscript && !hasSummary && file.isSynced) View.VISIBLE else View.GONE
        binding.continueAiButton.isEnabled = !processingThisFile
        binding.continueAiButton.text =
            if (processingThisFile) activeProcessing?.status.orEmpty() else "继续转录与总结"
        binding.continueAiButton.setOnClickListener { startTranscription(file) }

        if (hasInsights) {
            binding.emptyState.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.VISIBLE
            if (file.isSynced) {
                binding.emptyTitle.text = "生成录音洞察"
                binding.emptySubtitle.text = "转录完成后，总结和全文会分别展示。"
                binding.generateButton.visibility = View.VISIBLE
                binding.generateButton.isEnabled = true
                binding.generateButton.text = getString(R.string.generate_ai_insights)
                binding.generateButton.setOnClickListener { startTranscription(file) }
            } else {
                binding.emptyTitle.text = "录音仍在 PLAUD 设备中"
                binding.emptySubtitle.text =
                    "导入完成后即可播放、转录和总结；本次会导入设备中的全部待同步录音。"
                binding.generateButton.visibility = View.VISIBLE
                binding.generateButton.isEnabled = true
                binding.generateButton.text = "立即从设备导入"
                binding.generateButton.setOnClickListener { importPendingRecordings() }
            }
        }

        binding.sendToIdeaShellButton.visibility =
            if (transcriptPlainText != null && hasSummary) View.VISIBLE else View.GONE
        binding.sendToIdeaShellButton.text = ideaShellButtonText(file, false)
        binding.sendToIdeaShellButton.setOnClickListener { sendToIdeaShell(file) }

        // Audio player: only available once the file has a local audio file
        bindAudioPlayer(file)
    }

    private fun renderInsightsPage(file: RecordingFile) {
        val showTranscript = selectedInsightsPage == INSIGHTS_TRANSCRIPT && transcriptPlainText != null
        updateInsightsTab(binding.transcriptTab, showTranscript)
        updateInsightsTab(binding.summaryTab, !showTranscript)

        if (showTranscript) {
            val text = transcriptPlainText.orEmpty()
            val segmentCount = com.plaud.template.common.TranscriptText.segmentCount(file.transcriptJSON)
            val count = String.format(Locale.getDefault(), "%,d", text.length)
            binding.insightContentMeta.text = "$segmentCount 个片段 · $count 字"
            binding.insightContentText.text = text
        } else {
            val text = file.summaryText.orEmpty()
            val count = String.format(Locale.getDefault(), "%,d", text.length)
            binding.insightContentMeta.text = "AI 自动整理 · $count 字"
            binding.insightContentText.text = text
        }
    }

    private fun updateInsightsTab(tab: TextView, selected: Boolean) {
        tab.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else android.R.color.transparent)
        tab.setTextColor(
            ContextCompat.getColor(
                this,
                if (selected) R.color.text_primary else R.color.text_secondary
            )
        )
    }

    private fun importPendingRecordings() {
        if (deviceManager.connectionState.value !is DeviceConnectionState.Connected) {
            AlertDialog.Builder(this)
                .setTitle("PLAUD 尚未连接")
                .setMessage("请唤醒 NotePin，等待首页显示“已连接”后再重试。")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (syncManager.state.value.isActive) {
            Toast.makeText(this, "录音正在导入，请稍候", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        syncManager.startSync()
        Toast.makeText(this, "已开始通过蓝牙导入设备录音", Toast.LENGTH_SHORT).show()
        finish()
    }

    // MARK: - Transcription (upload \u2192 submit \u2192 poll, mirrors iOS)

    private fun startTranscription(file: RecordingFile) {
        val path = file.localPath
        if (path == null || !File(path).exists()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.generate_ai_insights))
                .setMessage("Local audio file not found. Sync the recording first.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (!aiProcessingManager.isSiliconFlowReady()) {
            AlertDialog.Builder(this)
                .setTitle("需要硅基流动 API Key")
                .setMessage("请先在“设置 → AI 与自动化”中填写 API Key。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, com.plaud.template.ui.settings.IntegrationSettingsActivity::class.java))
                }
                .show()
            return
        }
        if (!aiProcessingManager.start(file)) {
            Toast.makeText(this, "另一条录音正在处理中，请稍候", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeAiProcessing(fileId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                aiProcessingManager.state.collect { state ->
                    when (state) {
                        is AiProcessingState.Running -> if (state.fileId == fileId) {
                            binding.generateButton.isEnabled = false
                            binding.generateButton.text = "处理中…"
                            binding.emptySubtitle.text = state.status
                            binding.continueAiButton.isEnabled = false
                            binding.continueAiButton.text = state.status
                        }
                        is AiProcessingState.Completed -> if (state.fileId == fileId) {
                            aiProcessingManager.consumeTerminal(fileId)
                            syncManager.refreshFromStore()
                            loadFile(fileId)
                            Toast.makeText(this@FileDetailActivity, "转录、标题和总结已生成", Toast.LENGTH_SHORT).show()
                        }
                        is AiProcessingState.Failed -> if (state.fileId == fileId) {
                            aiProcessingManager.consumeTerminal(fileId)
                            syncManager.refreshFromStore()
                            loadFile(fileId)
                            binding.generateButton.isEnabled = true
                            binding.generateButton.text = getString(R.string.generate_ai_insights)
                            binding.emptySubtitle.text = "转录和总结这条录音"
                            showProcessingError(state.message)
                        }
                        AiProcessingState.Idle -> Unit
                    }
                }
            }
        }
    }

    private fun showProcessingError(message: String) {
        if (!isUiUsable()) return
        AlertDialog.Builder(this)
            .setTitle("处理失败")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sendToIdeaShell(file: RecordingFile) {
        val transcript = transcriptPlainText ?: return
        val summary = file.summaryText ?: return
        val updating = !file.ideaShellNoteId.isNullOrBlank()
        val integrations = AiIntegrations(this)
        if (!integrations.isIdeaShellReady()) {
            AlertDialog.Builder(this)
                .setTitle("需要闪念贝壳 MCP Token")
                .setMessage("请先在“设置 → AI 与自动化”中配置 MCP 地址和 Token。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(this, com.plaud.template.ui.settings.IntegrationSettingsActivity::class.java))
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (updating) "更新闪念贝壳笔记" else "保存到闪念贝壳")
            .setMessage(
                if (updating) "这条录音已保存过。本次会更新原笔记，不会创建重复内容。"
                else "标题：${file.name}\n\n${summary.take(500)}\n\n将保存总结和转录全文。"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (updating) "确认更新" else "确认保存") { _, _ ->
                binding.sendToIdeaShellButton.isEnabled = false
                binding.sendToIdeaShellButton.text = "正在保存…"
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            integrations.saveToIdeaShell(
                                file.name,
                                transcript,
                                summary,
                                file.ideaShellNoteId
                            )
                        }
                        com.plaud.template.storage.RecordingStore.updateIdeaShellReceipt(
                            file.id,
                            result.noteId,
                            System.currentTimeMillis()
                        )
                        file.ideaShellNoteId = result.noteId
                        file.ideaShellSyncedAt = System.currentTimeMillis()
                        if (!isUiUsable()) return@launch
                        binding.sendToIdeaShellButton.isEnabled = true
                        binding.sendToIdeaShellButton.text = ideaShellButtonText(file, false)
                        Toast.makeText(
                            this@FileDetailActivity,
                            if (result.updated) "已更新闪念贝壳笔记" else "已保存到闪念贝壳",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (!isUiUsable()) return@launch
                        binding.sendToIdeaShellButton.isEnabled = true
                        binding.sendToIdeaShellButton.text = ideaShellButtonText(file, false)
                        AlertDialog.Builder(this@FileDetailActivity)
                            .setTitle("发送失败")
                            .setMessage(error.message ?: "未知错误")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun ideaShellButtonText(file: RecordingFile, saving: Boolean): String = when {
        saving -> "正在保存…"
        !file.ideaShellNoteId.isNullOrBlank() -> "更新闪念贝壳"
        else -> "保存到闪念贝壳"
    }

    private fun isUiUsable(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private var transcriptionObserved = false

    private fun observeTranscription(file: RecordingFile) {
        if (transcriptionObserved) return
        transcriptionObserved = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.plaud.template.managers.TranscriptionManager.shared.state.collect { st ->
                    when (st) {
                        is com.plaud.template.managers.TranscriptionState.Uploading -> {
                            binding.emptySubtitle.text = "Uploading\u2026 ${(st.progress * 100).toInt()}%"
                        }
                        is com.plaud.template.managers.TranscriptionState.Submitting ->
                            binding.emptySubtitle.text = "Submitting transcription\u2026"
                        is com.plaud.template.managers.TranscriptionState.Processing ->
                            binding.emptySubtitle.text = "Transcribing\u2026 (${st.status})"
                        is com.plaud.template.managers.TranscriptionState.Completed -> {
                            binding.generateButton.isEnabled = true
                            com.plaud.template.managers.TranscriptionManager.shared.reset()
                            if (st.resultsJson == "[]" || st.resultsJson.isBlank()) {
                                // Backend finished but found no speech (VAD dropped everything) —
                                // tell the user instead of silently showing the empty state again.
                                binding.emptySubtitle.text = "Transcribe and summarize this recording."
                                AlertDialog.Builder(this@FileDetailActivity)
                                    .setTitle(getString(R.string.generate_ai_insights))
                                    .setMessage("Transcription finished, but no speech was detected in this recording.")
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            } else {
                                com.plaud.template.storage.RecordingStore.updateTranscript(file.id, st.resultsJson)
                                loadFile(file.id) // re-render with the parsed transcript
                            }
                        }
                        is com.plaud.template.managers.TranscriptionState.Failed -> {
                            binding.generateButton.isEnabled = true
                            binding.emptySubtitle.text = "Transcribe and summarize this recording."
                            com.plaud.template.managers.TranscriptionManager.shared.reset()
                            AlertDialog.Builder(this@FileDetailActivity)
                                .setTitle("Transcription Failed")
                                .setMessage(st.message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /** "Xm Ys" / "Xh Ym Zs" duration for the meta line. */
    private fun formatMetaDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0 || h > 0) append("${m}m ")
            append("${s}s")
        }.trim()
    }

    // MARK: - Audio Player

    // Media3 ExoPlayer: OEM MediaPlayerNative frequently fails on Ogg/Opus (the sync/export
    // format); ExoPlayer's own extractor + platform decoder handles it reliably.
    private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    private fun setupAudioPlayerControls() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.rewindButton.setOnClickListener { seekBy(-5_000) }
        binding.forwardButton.setOnClickListener { seekBy(5_000) }
        binding.progressSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = exoPlayer ?: return
                    val target = (progress / 1000f * p.duration).toLong().coerceAtLeast(0)
                    p.seekTo(target)
                    binding.currentTimeLabel.text = formatClock(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun bindAudioPlayer(file: RecordingFile) {
        val path = file.localPath
        val exists = path != null && File(path).exists()
        if (!exists) {
            binding.audioPlayer.visibility = View.GONE
            releasePlayer()
            return
        }
        binding.audioPlayer.visibility = View.VISIBLE
        // Avoid re-preparing the same file on every onResume
        if (preparedPath == path && exoPlayer != null) return

        // Self-heal legacy files exported with the SDK's corrupt OpusTags header
        if (path!!.endsWith(".opus", ignoreCase = true)) {
            com.plaud.template.common.OpusRepair.repairIfNeeded(path)
        }

        releasePlayer()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        exoPlayer = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_READY -> {
                        preparedPath = path
                        binding.totalTimeLabel.text = formatClock(player.duration.coerceAtLeast(0))
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        stopProgressUpdates()
                        binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                        player.pause()
                        player.seekTo(0)
                        binding.progressSlider.progress = 0
                        binding.currentTimeLabel.text = formatClock(0)
                    }
                    else -> {}
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                com.plaud.template.common.AppLog.w("FileDetail", "ExoPlayer error: ${error.errorCodeName}")
                binding.audioPlayer.visibility = View.GONE
                releasePlayer()
            }
        })
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(File(path!!))))
        player.prepare()
        binding.currentTimeLabel.text = formatClock(0)
        binding.progressSlider.progress = 0
    }

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            stopProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        } else {
            p.play()
            startProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun seekBy(deltaMs: Int) {
        val p = exoPlayer ?: return
        val duration = p.duration.coerceAtLeast(0)
        val target = (p.currentPosition + deltaMs).coerceIn(0, duration)
        p.seekTo(target)
        binding.currentTimeLabel.text = formatClock(target)
        binding.progressSlider.progress = if (duration > 0) (target * 1000 / duration).toInt() else 0
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val p = exoPlayer ?: return
                if (p.isPlaying) {
                    val duration = p.duration.coerceAtLeast(0)
                    binding.currentTimeLabel.text = formatClock(p.currentPosition)
                    binding.progressSlider.progress =
                        if (duration > 0) (p.currentPosition * 1000 / duration).toInt() else 0
                }
                progressHandler.postDelayed(this, 200)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null
        preparedPath = null
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000).toInt().coerceAtLeast(0)
        return String.format("%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    override fun onPause() {
        super.onPause()
        // Pause playback when leaving the screen
        exoPlayer?.takeIf { it.isPlaying }?.let {
            it.pause()
            stopProgressUpdates()
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "--:--"
        val total = seconds.toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    // MARK: - More Menu

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_detail, popup.menu)

        val file = currentFile ?: return

        // Hide options that do not apply (Export Audio stays visible like iOS; failure alerts)
        popup.menu.findItem(R.id.action_copy_summary)?.isVisible = file.summaryText != null
        popup.menu.findItem(R.id.action_copy_transcript)?.isVisible = transcriptPlainText != null
        popup.menu.findItem(R.id.action_delete)?.isVisible = file.isSynced
        popup.menu.findItem(R.id.action_delete_from_device)?.isVisible =
            DeviceManager.isSupportedDevice(file.deviceSN)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    exportAudio(file)
                    true
                }
                R.id.action_rename -> {
                    showRenameDialog(file)
                    true
                }
                R.id.action_copy_summary -> {
                    copySummary(file)
                    true
                }
                R.id.action_copy_transcript -> {
                    copyTranscript()
                    true
                }
                R.id.action_delete -> {
                    showDeleteConfirmation(file)
                    true
                }
                R.id.action_delete_from_device -> {
                    showDeviceDeleteConfirmation(file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun exportAudio(file: RecordingFile) {
        syncManager.exportAudio(file) { result ->
            runOnUiThread {
                if (!isUiUsable()) return@runOnUiThread
                result.onSuccess { outputFile ->
                    val uri = FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        outputFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.export_audio)))
                }
                result.onFailure {
                    // Alert instead of toast (mirrors iOS "Export Failed")
                    AlertDialog.Builder(this)
                        .setTitle("Export Failed")
                        .setMessage(it.message ?: "Could not export this recording.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun showRenameDialog(file: RecordingFile) {
        val editText = EditText(this).apply {
            setText(file.name)
            selectAll()
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(editText)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    syncManager.renameFile(file, newName)
                    loadFile(file.id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copySummary(file: RecordingFile) {
        // Silent copy (no toast, mirrors iOS / template-app convention)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Summary", file.summaryText))
    }

    private fun copyTranscript() {
        val text = transcriptPlainText ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    private fun showDeleteConfirmation(file: RecordingFile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_local_copy))
            .setMessage("只删除手机中的音频、转录和总结，不会删除 PLAUD 设备里的原录音。")
            .setPositiveButton(R.string.delete) { _, _ ->
                syncManager.deleteFile(file)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeviceDeleteConfirmation(file: RecordingFile) {
        val connected = deviceManager.connectedDevice.value
        if (deviceManager.connectionState.value !is DeviceConnectionState.Connected ||
            connected?.serialNumber != file.deviceSN
        ) {
            AlertDialog.Builder(this)
                .setTitle("PLAUD 尚未连接")
                .setMessage("请唤醒并连接保存这条录音的 PLAUD 设备后重试。")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (syncManager.state.value.isActive) {
            Toast.makeText(this, "录音正在传输，请完成后再删除", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_from_plaud))
            .setMessage(
                "将从 NotePin 永久删除“${file.name}”。" +
                    if (file.isSynced) "\n\n手机中已下载的音频、转录和总结会保留。" else ""
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                Toast.makeText(this, "正在从 PLAUD 删除…", Toast.LENGTH_SHORT).show()
                deviceManager.deleteRecordingFromDevice(file.sessionId) { result ->
                    runOnUiThread {
                        if (!isUiUsable()) return@runOnUiThread
                        result.onSuccess {
                            if (!file.isSynced) {
                                syncManager.deleteFile(file)
                                Toast.makeText(this, "录音已从 PLAUD 删除", Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                syncManager.fetchFileList()
                                AlertDialog.Builder(this)
                                    .setTitle("已从 PLAUD 删除")
                                    .setMessage("手机中的本地副本、转录和总结已保留。")
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                        result.onFailure { error ->
                            AlertDialog.Builder(this)
                                .setTitle("设备删除失败")
                                .setMessage(error.message ?: "PLAUD 没有确认删除请求。")
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    private companion object {
        const val INSIGHTS_TRANSCRIPT = "transcript"
        const val INSIGHTS_SUMMARY = "summary"
    }
}
