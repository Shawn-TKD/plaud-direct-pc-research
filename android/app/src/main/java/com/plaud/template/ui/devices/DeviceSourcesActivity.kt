package com.plaud.template.ui.devices

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import com.plaud.template.common.OfflineAuthStore
import com.plaud.template.common.JwtUtils
import com.plaud.template.databinding.ActivityDeviceSourcesBinding
import com.plaud.template.models.displayName
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.onboarding.ScanningActivity
import kotlinx.coroutines.launch

class DeviceSourcesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDeviceSourcesBinding
    private val app get() = application as PlaudTemplateApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.plaudCard.root.setOnClickListener {
            val material = OfflineAuthStore.loadAny(this)
            if (material == null && !com.plaud.template.BuildConfig.NOTE_PRO_BOOTSTRAP) {
                startActivity(Intent(this, PlaudOfflineSetupActivity::class.java))
            } else {
                val userId = material?.bindToken
                    ?: JwtUtils.parse(RecordingStore.activeUserAccessToken)?.sub.orEmpty()
                if (userId.isBlank()) {
                    android.widget.Toast.makeText(this, "请先在设置中填写 User Access Token", android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                RecordingStore.userId = userId
                (application as PlaudTemplateApp).deviceManager.configure(userId)
                startActivity(Intent(this, ScanningActivity::class.java).putExtra(
                    ScanningActivity.EXTRA_ADDING_DEVICE, true
                ))
            }
        }
        binding.dingtalkCard.root.setOnClickListener {
            startActivity(Intent(this, DingTalkA1Activity::class.java))
        }
        binding.feishuCard.root.setOnClickListener {
            startActivity(Intent(this, FeishuRecorderActivity::class.java))
        }
        observeConnectionStates()
    }

    private fun observeConnectionStates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.deviceManager.connectedDevice.collect { device ->
                        renderStatus(
                            binding.plaudCard.statusLabel,
                            device != null,
                            if (device != null) "已连接 · ${device.displayName}" else "未连接 · 点按管理"
                        )
                    }
                }
                launch {
                    app.peripheralSessionHub.dingTalkA1Client.connectionState.collect { connected ->
                        renderStatus(
                            binding.dingtalkCard.statusLabel,
                            connected,
                            if (connected) "已连接 · 离线 BLE 会话保持中" else "未连接 · 点按连接"
                        )
                    }
                }
                launch {
                    app.peripheralSessionHub.d3200Client.connectionState.collect { connected ->
                        renderStatus(
                            binding.feishuCard.statusLabel,
                            connected,
                            if (connected) "已连接 · 本地 BLE 会话保持中" else "未连接 · 点按连接"
                        )
                    }
                }
            }
        }
    }

    private fun renderStatus(label: android.widget.TextView, connected: Boolean, text: String) {
        label.text = text
        label.setTextColor(ContextCompat.getColor(this, if (connected) R.color.green else R.color.text_secondary))
    }
}
