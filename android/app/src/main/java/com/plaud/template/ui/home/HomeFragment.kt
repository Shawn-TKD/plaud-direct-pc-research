package com.plaud.template.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.common.WifiTransferPermission
import com.plaud.template.databinding.FragmentHomeBinding
import com.plaud.template.models.*
import com.plaud.template.ui.filedetail.FileDetailActivity
import com.plaud.template.ui.devices.DeviceSourcesActivity
import com.plaud.template.ui.devices.DingTalkA1Activity
import com.plaud.template.ui.devices.FeishuRecorderActivity
import com.plaud.template.ui.recording.RecordingActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Home Tab — Device card + Recording entry + Banners + Recent Files
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val app get() = requireActivity().application as PlaudTemplateApp
    private val deviceManager get() = app.deviceManager
    private val recordingManager get() = app.recordingManager
    private val syncManager get() = app.syncManager

    private var isDeviceCardExpanded = false
    private var currentDevice: PlaudDevice? = null
    private var recordingTimerHandler: Handler? = null
    private var recordingTimerRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeManagers()
    }

    override fun onResume() {
        super.onResume()
        // Re-read the paired-device list on every return to Home (mirrors iOS viewWillAppear),
        // so unpair/add-device done on other screens is reflected immediately.
        renderOtherDevices()
    }

    private fun setupClickListeners() {
        // Expand/collapse the device card
        binding.deviceCardHeader.setOnClickListener { toggleDeviceCard() }

        // Manage device
        binding.manageButton.setOnClickListener {
            currentDevice?.let { device ->
                val intent = Intent(requireContext(), DevicePanelActivity::class.java)
                intent.putExtra("device_sn", device.serialNumber)
                intent.putExtra("device_name", device.displayName)
                intent.putExtra("firmware_version", device.firmwareVersion)
                intent.putExtra("latest_firmware", device.latestFirmwareVersion)
                startActivity(intent)
            }
        }

        // Recording entry
        binding.recordCard.setOnClickListener { openRecording() }

        // Fast Transfer button (inside the syncBanner include)
        binding.syncBanner.fastTransferButton.setOnClickListener {
            showFastTransferDialog()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun selectableBackgroundRes(): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun makeSeparator(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(resources.getColor(R.color.light_gray, null))
    }

    /** PLAUD remains single-active; A1 and D3200 are independent native BLE sessions. */
    private fun renderOtherDevices() {
        val container = binding.otherDevicesList
        container.removeAllViews()
        container.addView(makeSeparator())
        container.addView(makePeripheralRow(
            title = "钉钉 A1",
            connected = app.peripheralSessionHub.dingTalkA1Client.connectionState.value,
            target = DingTalkA1Activity::class.java
        ))
        container.addView(makeSeparator())
        container.addView(makePeripheralRow(
            title = "飞书录音豆 D3200",
            connected = app.peripheralSessionHub.d3200Client.connectionState.value,
            target = FeishuRecorderActivity::class.java
        ))
        container.addView(makeSeparator())
        container.addView(makeAddDeviceRow())
    }

    private fun makePeripheralRow(title: String, connected: Boolean, target: Class<*>): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(64)
            setPaddingRelative(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setBackgroundResource(selectableBackgroundRes())
            setOnClickListener { startActivity(Intent(requireContext(), target)) }
        }
        val icon = TextView(requireContext()).apply {
            text = if (title.startsWith("钉钉")) "A1" else "豆"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(R.color.black, null))
            setBackgroundResource(R.drawable.bg_logo_placeholder)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        val labels = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }
        labels.addView(TextView(requireContext()).apply {
            text = title
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(resources.getColor(R.color.black, null))
        })
        labels.addView(TextView(requireContext()).apply {
            text = if (connected) "已连接 · 后台保持" else "未连接"
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
            setTextColor(resources.getColor(if (connected) R.color.green else R.color.text_secondary, null))
        })
        val chevron = TextView(requireContext()).apply {
            text = "›"
            textSize = 24f
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        row.addView(icon)
        row.addView(labels)
        row.addView(chevron)
        return row
    }

    private fun makeAddDeviceRow(): View = TextView(requireContext()).apply {
        text = "管理与添加录音设备"
        textSize = 14f
        setTextColor(resources.getColor(R.color.black, null))
        gravity = android.view.Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        setPaddingRelative(dp(16), 0, dp(16), 0)
        isClickable = true
        setBackgroundResource(selectableBackgroundRes())
        setOnClickListener {
            startActivity(Intent(requireContext(), DeviceSourcesActivity::class.java))
        }
    }

    // MARK: - Data binding

    private fun observeManagers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Device state
                launch {
                    deviceManager.connectedDevice.collect { device ->
                        currentDevice = device
                        updateDeviceCard(device)
                    }
                }

                launch {
                    app.peripheralSessionHub.dingTalkA1Client.connectionState.collect {
                        renderOtherDevices()
                    }
                }

                launch {
                    app.peripheralSessionHub.d3200Client.connectionState.collect {
                        renderOtherDevices()
                    }
                }

                // Recording state (updates the record card only; matches iOS — iOS does not show a separate recording banner)
                launch {
                    recordingManager.state.collect { state ->
                        updateRecordCard(state)
                    }
                }

                // Sync state
                launch {
                    syncManager.state.collect { state ->
                        updateSyncBanner(state)
                    }
                }

                // File list
                launch {
                    syncManager.files.collect { files ->
                        val synced = files.filter { it.isSynced }.take(5)
                        updateRecentFiles(synced)
                    }
                }
            }
        }
    }

    // MARK: - Device card

    private fun updateDeviceCard(device: PlaudDevice?) {
        renderOtherDevices()
        if (device == null) {
            binding.deviceNameLabel.text = getString(R.string.no_device)
            binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot_gray)
            binding.statusLabel.text = getString(R.string.disconnected)
            return
        }
        binding.deviceNameLabel.text = device.displayName
        binding.statusLabel.text = getString(R.string.connected)

        // Battery level. A negative level means "not reported yet" (the SDK hands us -1 until the
        // device sends it, which showed up as "-1%" on NotePin S \u2014 PLA2-312): show a placeholder
        // and a neutral, empty bar instead of a nonsense number.
        val chargingPrefix = if (device.isCharging) "\u26A1 " else ""
        val hasBattery = device.batteryLevel >= 0
        binding.batteryValueLabel.text =
            if (hasBattery) "$chargingPrefix${device.batteryLevel}%" else "$chargingPrefix--"

        // Battery bar color
        val batteryColor = when {
            !hasBattery -> R.color.text_secondary
            device.batteryLevel <= 10 -> R.color.red
            device.batteryLevel <= 20 -> R.color.orange
            else -> R.color.green
        }
        binding.batteryFill.setBackgroundColor(ContextCompat.getColor(requireContext(), batteryColor))

        // Battery bar width
        binding.batteryFill.post {
            val parent = binding.batteryFill.parent as? View ?: return@post
            val parentWidth = parent.width
            val level = device.batteryLevel.coerceAtLeast(0)
            val fillWidth = (parentWidth * level / 100f).toInt().coerceAtLeast(2)
            binding.batteryFill.layoutParams = binding.batteryFill.layoutParams.apply { width = fillWidth }
        }

        // Storage
        val usedGB = String.format("%.1f", device.storageUsed / 1_073_741_824.0)
        val totalGB = String.format("%.1f", device.storageTotal / 1_073_741_824.0)
        binding.storageValueLabel.text = "$usedGB GB / $totalGB GB"

        // Storage bar width
        binding.storageFill.post {
            val parent = binding.storageFill.parent as? View ?: return@post
            val parentWidth = parent.width
            val ratio = device.storageUsageRatio
            val fillWidth = (parentWidth * ratio).toInt().coerceAtLeast(2)
            binding.storageFill.layoutParams = binding.storageFill.layoutParams.apply { width = fillWidth }
        }
    }

    private fun toggleDeviceCard() {
        isDeviceCardExpanded = !isDeviceCardExpanded
        android.transition.TransitionManager.endTransitions(binding.root as ViewGroup)
        android.transition.TransitionManager.beginDelayedTransition(
            binding.root as ViewGroup,
            android.transition.AutoTransition().setDuration(180)
        )
        binding.deviceExpandedContent.visibility = if (isDeviceCardExpanded) View.VISIBLE else View.GONE

        // Chevron rotation
        val rotation = if (isDeviceCardExpanded) 90f else 0f
        ObjectAnimator.ofFloat(binding.deviceChevron, View.ROTATION, rotation).apply {
            duration = 250
            start()
        }
    }

    // MARK: - Recording entry card

    private fun updateRecordCard(state: RecordingState) {
        when (state) {
            is RecordingState.Recording -> {
                binding.recordTitleLabel.text = getString(R.string.recording_ellipsis)
                startRecordCardTimer(state.startedAt)
            }
            else -> {
                binding.recordTitleLabel.text = getString(R.string.capture_moments)
                binding.recordSubtitleLabel.text = getString(R.string.record_via_device)
                stopRecordCardTimer()
            }
        }
    }

    private fun startRecordCardTimer(startedAt: Long) {
        stopRecordCardTimer()
        recordingTimerHandler = Handler(Looper.getMainLooper())
        recordingTimerRunnable = object : Runnable {
            override fun run() {
                val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                binding.recordSubtitleLabel.text = String.format("%02d:%02d:%02d", h, m, s)
                recordingTimerHandler?.postDelayed(this, 1000)
            }
        }
        recordingTimerHandler?.post(recordingTimerRunnable!!)
    }

    private fun stopRecordCardTimer() {
        recordingTimerRunnable?.let { recordingTimerHandler?.removeCallbacks(it) }
        recordingTimerHandler = null
        recordingTimerRunnable = null
    }

    // MARK: - Sync Banner

    private fun updateSyncBanner(state: SyncState) {
        val bannerRoot = binding.syncBanner.root
        when (state) {
            is SyncState.Syncing -> {
                fadeInBanner(bannerRoot)
                updateSyncBannerContent(state.progress, isWiFi = false)
            }
            is SyncState.WiFiTransferring -> {
                fadeInBanner(bannerRoot)
                updateSyncBannerContent(state.progress, isWiFi = true)
            }
            // Keep the banner untouched during the WiFi connect window (mirrors iOS —
            // the FastTransferSheet owns that phase; hiding here would make it flash)
            is SyncState.WiFiConnecting -> {}
            is SyncState.Completed -> {
                bannerRoot.postDelayed({ fadeOutBanner(bannerRoot) }, 2000)
            }
            else -> fadeOutBanner(bannerRoot)
        }
    }

    /** Atomic visibility keeps rapid BLE/WiFi state changes from racing old end actions. */
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

    private fun updateSyncBannerContent(progress: SyncProgress, isWiFi: Boolean) {
        val banner = binding.syncBanner
        val isDingTalkA1 = progress.currentFileName?.startsWith("A1 ") == true
        banner.syncTitleLabel.text = when {
            isDingTalkA1 -> "正在导入钉钉 A1 录音"
            isWiFi -> getString(R.string.fast_transfer)
            else -> getString(R.string.syncing_recordings)
        }
        banner.syncCountLabel.text =
            if (progress.totalFiles > 0) "${progress.syncedFiles}/${progress.totalFiles}" else ""

        // Progress bar
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

    // MARK: - Recent Files

    private fun updateRecentFiles(files: List<RecordingFile>) {
        binding.recentFilesList.removeAllViews()
        binding.emptyFilesLabel.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.recentFilesList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE

        for (file in files) {
            val row = createFileRow(file)
            binding.recentFilesList.addView(row)
        }
    }

    private fun createFileRow(file: RecordingFile): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_file_row, binding.recentFilesList, false)
        row.findViewById<TextView>(R.id.fileNameLabel).text = file.name
        row.findViewById<TextView>(R.id.fileMetaLabel).text = formatMeta(file)
        row.setOnClickListener {
            val intent = Intent(requireContext(), FileDetailActivity::class.java)
            intent.putExtra("file_id", file.id)
            startActivity(intent)
        }
        return row
    }

    private fun formatMeta(file: RecordingFile): String {
        val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val date = Date(file.createdAt)
        val dur = formatDuration(file.duration)
        return "${dateFormat.format(date)}  \u00B7  ${timeFormat.format(date)}  \u00B7  $dur"
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

    // MARK: - Navigation

    private fun openRecording() {
        startActivity(Intent(requireContext(), RecordingActivity::class.java))
    }

    private fun showFastTransferDialog() {
        // Honor "Never show again": skip the sheet and start directly.
        if (com.plaud.template.storage.RecordingStore.fastTransferNeverShowAgain &&
            WifiTransferPermission.isGranted(requireContext())) {
            syncManager.startWiFiTransfer()
            return
        }
        FastTransferSheet().show(childFragmentManager, "FastTransferSheet")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopRecordCardTimer()
        _binding = null
    }
}
