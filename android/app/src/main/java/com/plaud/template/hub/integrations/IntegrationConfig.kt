package com.plaud.template.hub.integrations

import android.content.Context
import java.net.URI

data class IntegrationConfig(
    val ideaShellUrl: String = DEFAULT_IDEASHELL_URL,
    val siliconFlowModel: String = "FunAudioLLM/SenseVoiceSmall",
    val siliconFlowSummaryModel: String = "deepseek-ai/DeepSeek-V3.2",
    val deepSeekModel: String = "deepseek-chat"
) {
    fun validatedIdeaShellUri(): URI {
        val uri = URI(ideaShellUrl.trim())
        require(uri.scheme == "https") { "MCP 地址必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "MCP 地址缺少主机名" }
        require(uri.userInfo == null) { "MCP 地址不能包含账号或令牌" }
        return uri
    }
    companion object { const val DEFAULT_IDEASHELL_URL = "https://api.ideashell.cn/ideashell/mcp" }
}

class IntegrationConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("hub_integrations", Context.MODE_PRIVATE)
    fun load() = IntegrationConfig(
        prefs.getString("ideashell_url", IntegrationConfig.DEFAULT_IDEASHELL_URL) ?: IntegrationConfig.DEFAULT_IDEASHELL_URL,
        prefs.getString("siliconflow_model", "FunAudioLLM/SenseVoiceSmall") ?: "FunAudioLLM/SenseVoiceSmall",
        prefs.getString("siliconflow_summary_model", "deepseek-ai/DeepSeek-V3.2") ?: "deepseek-ai/DeepSeek-V3.2",
        prefs.getString("deepseek_model", "deepseek-chat") ?: "deepseek-chat"
    )
    fun save(value: IntegrationConfig) {
        value.validatedIdeaShellUri()
        prefs.edit().putString("ideashell_url", value.ideaShellUrl.trim())
            .putString("siliconflow_model", value.siliconFlowModel.trim())
            .putString("siliconflow_summary_model", value.siliconFlowSummaryModel.trim())
            .putString("deepseek_model", value.deepSeekModel.trim()).apply()
    }
}
