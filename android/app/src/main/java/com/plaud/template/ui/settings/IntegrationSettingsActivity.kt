package com.plaud.template.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.plaud.template.hub.automation.AutomationPolicy
import com.plaud.template.hub.automation.AutomationPolicyStore
import com.plaud.template.hub.integrations.AiIntegrations
import com.plaud.template.hub.integrations.IntegrationConfig
import com.plaud.template.hub.integrations.IntegrationConfigStore
import com.plaud.template.hub.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 密钥不回显；新值通过 Android Keystore 加密保存。 */
class IntegrationSettingsActivity : AppCompatActivity() {
    private lateinit var vault: SecretVault
    private lateinit var configStore: IntegrationConfigStore
    private lateinit var policyStore: AutomationPolicyStore
    private lateinit var siliconKey: EditText
    private lateinit var deepSeekKey: EditText
    private lateinit var asrModel: EditText
    private lateinit var summaryModel: EditText
    private lateinit var mcpUrl: EditText
    private lateinit var mcpToken: EditText
    private val switches = mutableMapOf<String, SwitchCompat>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = SecretVault(this); configStore = IntegrationConfigStore(this); policyStore = AutomationPolicyStore(this)
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val config = configStore.load(); val policy = policyStore.load()
        return ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(30), dp(24), dp(48))
                addView(TextView(context).apply { text = "‹"; textSize = 34f; setTextColor(Color.BLACK); setOnClickListener { finish() } })
                addView(title("AI 与自动化", 34f))
                addView(caption("音频默认留在本机；只有转录、总结和发送到闪念贝壳时才联网。"))
                addView(section("硅基流动"))
                siliconKey = secretField("API Key", vault.isConfigured(SecretVault.SILICONFLOW_API_KEY)); addView(siliconKey)
                asrModel = field("转录模型", config.siliconFlowModel); addView(asrModel)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(context).apply {
                        text = "免费快速"; isAllCaps = false
                        setOnClickListener { asrModel.setText("FunAudioLLM/SenseVoiceSmall") }
                        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
                    })
                    addView(Button(context).apply {
                        text = "付费高精度"; isAllCaps = false
                        setOnClickListener { asrModel.setText("Qwen/Qwen3-Omni-30B-A3B-Instruct") }
                        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
                    })
                })
                addView(caption("长录音会自动按完整 MP3 帧分段。免费 ASR 每段约 45 分钟；Qwen3-Omni 每段约 30 分钟并按音频 Token 计费。"))
                summaryModel = field("总结模型", config.siliconFlowSummaryModel); addView(summaryModel)
                addView(section("DeepSeek（可选）"))
                addView(caption("填写后优先使用 DeepSeek 总结；留空则使用硅基流动的总结模型。"))
                deepSeekKey = secretField("DeepSeek API Key", vault.isConfigured(SecretVault.DEEPSEEK_API_KEY)); addView(deepSeekKey)
                addView(section("闪念贝壳 MCP"))
                mcpUrl = field("MCP HTTPS 地址", config.ideaShellUrl); addView(mcpUrl)
                mcpToken = secretField("Bearer Token", vault.isConfigured(SecretVault.IDEASHELL_BEARER_TOKEN)); addView(mcpToken)
                addView(Button(context).apply {
                    text = "测试已保存的 MCP 连接"; isAllCaps = false
                    setOnClickListener { testIdeaShellConnection(this) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply { topMargin = dp(2) }
                })
                addView(caption("测试只会检查握手和笔记工具，不会创建或修改闪念。新地址或 Token 请先保存。"))
                addView(section("自动处理"))
                addSwitch("autoConnect", "设备出现时自动连接", policy.autoConnect)
                addSwitch("autoDownload", "自动下载新录音", policy.autoDownload)
                addSwitch("wifiOnly", "仅在 Wi‑Fi 下转录", policy.transcribeOnWifiOnly)
                addSwitch("autoTranscribe", "下载后自动转录", policy.autoTranscribe)
                addSwitch("autoSummarize", "转录后自动总结", policy.autoSummarize)
                addSwitch("ideaShell", "完成后提供发送到闪念贝壳", policy.offerIdeaShellExport)
                addSwitch("confirmMcp", "写入闪念贝壳前必须确认", true, locked = true)
                addSwitch("keepAudio", "保留本地原始音频", policy.keepOriginalAudio)
                addView(Button(context).apply {
                    text = "加密保存设置"; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
                    setOnClickListener { save() }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(28) }
                })
            })
        }
    }

    private fun save() {
        val current = configStore.load()
        val config = IntegrationConfig(mcpUrl.text.toString().trim(), asrModel.text.toString().trim().ifBlank { current.siliconFlowModel },
            summaryModel.text.toString().trim().ifBlank { current.siliconFlowSummaryModel }, current.deepSeekModel)
        runCatching { configStore.save(config) }.onFailure { mcpUrl.error = it.message; return }
        saveSecret(siliconKey, SecretVault.SILICONFLOW_API_KEY); saveSecret(deepSeekKey, SecretVault.DEEPSEEK_API_KEY)
        saveSecret(mcpToken, SecretVault.IDEASHELL_BEARER_TOKEN)
        policyStore.save(AutomationPolicy(
            switches.getValue("autoConnect").isChecked, switches.getValue("autoDownload").isChecked,
            switches.getValue("wifiOnly").isChecked, switches.getValue("autoTranscribe").isChecked,
            switches.getValue("autoSummarize").isChecked, switches.getValue("ideaShell").isChecked,
            true, switches.getValue("keepAudio").isChecked
        ))
        Toast.makeText(this, "设置已加密保存在本机", Toast.LENGTH_SHORT).show(); finish()
    }

    private fun saveSecret(field: EditText, name: String) { field.text.toString().trim().takeIf(String::isNotEmpty)?.let { vault.put(name, it) } }

    private fun testIdeaShellConnection(button: Button) {
        button.isEnabled = false
        button.text = "正在测试…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { AiIntegrations(this@IntegrationSettingsActivity).listIdeaShellTools() }
            }
            button.isEnabled = true
            button.text = "测试已保存的 MCP 连接"
            result.onSuccess { tools ->
                val names = tools.mapTo(hashSetOf()) { it.name }
                if ("note_create" in names && "note_update" in names) {
                    Toast.makeText(
                        this@IntegrationSettingsActivity,
                        "连接正常，支持创建和更新闪念",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    AlertDialog.Builder(this@IntegrationSettingsActivity)
                        .setTitle("连接成功，但缺少笔记工具")
                        .setMessage("服务当前没有同时提供 note_create 和 note_update。")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }.onFailure { error ->
                AlertDialog.Builder(this@IntegrationSettingsActivity)
                    .setTitle("MCP 连接失败")
                    .setMessage(error.message ?: "未知错误")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
    private fun LinearLayout.addSwitch(key: String, label: String, checked: Boolean, locked: Boolean = false) {
        addView(SwitchCompat(context).apply { text = label; textSize = 15f; isChecked = checked; isEnabled = !locked; setPadding(dp(2), dp(9), dp(2), dp(9)); switches[key] = this })
    }
    private fun secretField(label: String, configured: Boolean) = field(if (configured) "$label（已配置，留空保持不变）" else label, "")
        .apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(true); setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(247, 247, 247))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }
    private fun section(text: String) = title(text, 18f).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28); bottomMargin = dp(10) } }
    private fun title(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.BLACK); gravity = Gravity.START }
    private fun caption(value: String) = TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, 0) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
