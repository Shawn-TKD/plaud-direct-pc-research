package com.plaud.template.ui.devices

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.databinding.ActivityPlaudOfflineSetupBinding
import com.plaud.template.common.PlaudPortableImporter
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.onboarding.ScanningActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaudOfflineSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaudOfflineSetupBinding
    private var packageText: String? = null

    private val choosePackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            packageText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取所选文件")
            binding.packageLabel.text = uri.lastPathSegment?.substringAfterLast('/') ?: "已选择凭据包"
            binding.importButton.isEnabled = true
            showStatus("已选择加密凭据包，请输入传输密码")
        }.onFailure { showStatus(it.message ?: "读取失败", true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaudOfflineSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        binding.selectButton.setOnClickListener {
            choosePackage.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
        }
        binding.importButton.setOnClickListener { importAndConnect() }
    }

    private fun importAndConnect() {
        val text = packageText ?: return showStatus("请先选择 plaud-device-auth.portable.json", true)
        val password = binding.passwordInput.text?.toString()?.toCharArray() ?: CharArray(0)
        binding.progressBar.visibility = View.VISIBLE
        binding.importButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                showStatus("正在解密并写入 Android Keystore…")
                withContext(Dispatchers.Default) {
                    PlaudPortableImporter.importPackage(this@PlaudOfflineSetupActivity, text, password)
                }
            }.onSuccess { result ->
                binding.passwordInput.text?.clear()
                RecordingStore.userId = com.plaud.template.common.OfflineAuthStore.loadAny(this@PlaudOfflineSetupActivity)?.bindToken
                val userId = RecordingStore.userId.orEmpty()
                (application as PlaudTemplateApp).deviceManager.configure(userId)
                showStatus("离线凭据已保存（设备 …${result.suffix}），正在扫描…")
                startActivity(Intent(this@PlaudOfflineSetupActivity, ScanningActivity::class.java).putExtra(
                    ScanningActivity.EXTRA_ADDING_DEVICE, true
                ))
                finish()
            }.onFailure {
                showStatus(it.message ?: "导入失败", true)
                binding.importButton.isEnabled = true
            }
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun showStatus(message: String, error: Boolean = false) {
        binding.statusLabel.text = message
        binding.statusLabel.setTextColor(getColor(if (error) com.plaud.template.R.color.red else com.plaud.template.R.color.text_primary))
    }
}
