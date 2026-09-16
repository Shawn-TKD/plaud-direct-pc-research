package com.plaud.template.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.BuildConfig
import com.plaud.template.managers.DeviceManager
import com.plaud.template.databinding.ActivityWelcomeBinding
import com.plaud.template.storage.RecordingStore
import com.plaud.template.ui.main.MainActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Onboarding first page — Welcome
 * Ready to use once B2B customers replace the logo and brand name
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Route BEFORE inflating so returning users never see a Welcome flash (mirrors iOS
        // deciding the root VC before the window is shown).
        // Route on ANY paired device (mirrors iOS pairedDeviceSNs check), not just the last-connected one.
        val hasPairedDevice = RecordingStore.pairedDeviceSNs.isNotEmpty()
        val savedUserId = RecordingStore.userId
        if ((hasPairedDevice || RecordingStore.hasSkippedOnboarding) && savedUserId != null) {
            (application as PlaudTemplateApp).deviceManager.configure(savedUserId)
            navigateToMain()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets (status bar top + navigation bar bottom)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.getStartedButton.setOnClickListener {
            if (com.plaud.template.BuildConfig.NOTE_PRO_BOOTSTRAP &&
                RecordingStore.activeUserAccessToken.isBlank()
            ) {
                showBootstrapTokenDialog()
            } else {
                onGetStartedTapped()
            }
        }

        if (com.plaud.template.BuildConfig.NOTE_PRO_BOOTSTRAP) {
            binding.skipButton.visibility = android.view.View.GONE
        }

        // Enter Home without pairing (e.g. the built-in token expired after a reinstall and
        // scanning/connect can't proceed — the user needs Settings to replace the token first).
        binding.skipButton.setOnClickListener {
            val userId = extractUserId()
            RecordingStore.userId = userId
            RecordingStore.hasSkippedOnboarding = true
            (application as PlaudTemplateApp).deviceManager.configure(userId)
            navigateToMain()
        }
    }

    private fun showBootstrapTokenDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "User Access Token (JWT)"
            setPadding(48, 32, 48, 32)
            maxLines = 4
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Note Pro 首次引导")
            .setView(input)
            .setPositiveButton("继续") { _, _ ->
                val token = input.text.toString().trim()
                val info = com.plaud.template.common.JwtUtils.parse(token)
                if (info == null || info.expSeconds * 1000 <= System.currentTimeMillis()) {
                    android.widget.Toast.makeText(this, "User Token 无效或已过期", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    RecordingStore.userAccessTokenOverride = token
                    onGetStartedTapped()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onGetStartedTapped() {
        // Extract sub from the partnerToken JWT payload to use as the userId
        val userId = extractUserId()
        RecordingStore.userId = userId

        val app = application as PlaudTemplateApp
        app.deviceManager.configure(userId)

        if (BuildConfig.NOTE_PRO_BOOTSTRAP) {
            showBootstrapOptions()
        } else {
            navigateToDeviceSources()
        }
    }

    private fun navigateToDeviceSources() {
        RecordingStore.hasSkippedOnboarding = true
        startActivity(Intent(this, com.plaud.template.ui.devices.DeviceSourcesActivity::class.java))
    }

    private fun showBootstrapOptions() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Note Pro 引导方式")
            .setMessage("可先用官方 SDK 准备电脑凭据，无需让手机占用 Note Pro 蓝牙连接。")
            .setPositiveButton("仅准备电脑凭据") { _, _ -> showBootstrapSnDialog() }
            .setNeutralButton("手机连接验证") { _, _ -> navigateToDeviceSources() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBootstrapSnDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "881 开头的完整 Note Pro 序列号"
            setSingleLine(true)
            setText(BuildConfig.BOOTSTRAP_SN)
            setPadding(48, 32, 48, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("准备电脑直连凭据")
            .setView(input)
            .setPositiveButton("开始") { _, _ ->
                val sn = input.text.toString().trim()
                if (!sn.matches(Regex("881[A-Za-z0-9_-]+"))) {
                    android.widget.Toast.makeText(this, "请输入完整的 Note Pro 序列号", android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val progress = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage("正在通过 PLAUD SDK 获取并加密保存凭据…")
                    .setCancelable(false)
                    .create()
                progress.show()
                lifecycleScope.launch {
                    val outcome = runCatching { DeviceManager.shared.prepareNoteProForPc(sn) }
                    progress.dismiss()
                    val message = outcome.fold(
                        onSuccess = { exported ->
                            if (exported) "电脑公钥加密的导出包已生成。现在可在电脑上导入并尝试连接。"
                            else "凭据已加密保存在手机。将电脑公钥放入应用后重启，即可生成导出包。"
                        },
                        onFailure = { "准备失败：${it.message ?: "请检查网络和 User Token"}" }
                    )
                    androidx.appcompat.app.AlertDialog.Builder(this@WelcomeActivity)
                        .setMessage(message)
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Parse the sub field from the partnerToken JWT payload to use as the userId
     */
    private fun extractUserId(): String {
        return try {
            val token = PARTNER_TOKEN
            val parts = token.split(".")
            if (parts.size < 2) return java.util.UUID.randomUUID().toString()

            // Base64-decode the payload (padding is added automatically)
            val payload = parts[1]
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            json.optString("sub", java.util.UUID.randomUUID().toString())
        } catch (e: Exception) {
            java.util.UUID.randomUUID().toString()
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        /** User Access Token, injected via local.properties → BuildConfig (mirrors iOS xcconfig).
         *  B2B customers put their own token in local.properties: PLAUD_USER_ACCESS_TOKEN=... */
        private val PARTNER_TOKEN get() = com.plaud.template.storage.RecordingStore.activeUserAccessToken
    }
}
