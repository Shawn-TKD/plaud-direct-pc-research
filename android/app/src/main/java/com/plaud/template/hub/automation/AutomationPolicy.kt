package com.plaud.template.hub.automation

import android.content.Context

data class AutomationPolicy(
    val autoConnect: Boolean = true,
    val autoDownload: Boolean = true,
    val transcribeOnWifiOnly: Boolean = true,
    val autoTranscribe: Boolean = true,
    val autoSummarize: Boolean = true,
    val offerIdeaShellExport: Boolean = true,
    val requireConfirmationBeforeMcpWrite: Boolean = true,
    val keepOriginalAudio: Boolean = true
)

class AutomationPolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("hub_automation", Context.MODE_PRIVATE)

    fun load() = AutomationPolicy(
        autoConnect = prefs.getBoolean("auto_connect", true),
        autoDownload = prefs.getBoolean("auto_download", true),
        transcribeOnWifiOnly = prefs.getBoolean("wifi_only", true),
        autoTranscribe = prefs.getBoolean("auto_transcribe", true),
        autoSummarize = prefs.getBoolean("auto_summarize", true),
        offerIdeaShellExport = prefs.getBoolean("ideashell_export", true),
        requireConfirmationBeforeMcpWrite = prefs.getBoolean("confirm_mcp_write", true),
        keepOriginalAudio = prefs.getBoolean("keep_audio", true)
    )

    fun save(value: AutomationPolicy) {
        prefs.edit()
            .putBoolean("auto_connect", value.autoConnect)
            .putBoolean("auto_download", value.autoDownload)
            .putBoolean("wifi_only", value.transcribeOnWifiOnly)
            .putBoolean("auto_transcribe", value.autoTranscribe)
            .putBoolean("auto_summarize", value.autoSummarize)
            .putBoolean("ideashell_export", value.offerIdeaShellExport)
            .putBoolean("confirm_mcp_write", value.requireConfirmationBeforeMcpWrite)
            .putBoolean("keep_audio", value.keepOriginalAudio)
            .apply()
    }
}
