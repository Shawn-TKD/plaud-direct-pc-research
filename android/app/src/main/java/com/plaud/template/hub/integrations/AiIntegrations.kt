package com.plaud.template.hub.integrations

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.plaud.template.common.AppLog
import com.plaud.template.common.TranscriptText
import com.plaud.template.hub.security.SecretVault
import com.plaud.template.models.RecordingFile
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AiTranscriptSegment(val startSeconds: Double, val durationSeconds: Double, val text: String)
data class AiRecordingResult(
    val segments: List<AiTranscriptSegment>,
    val summary: String,
    val title: String
) {
    val transcript: String get() = segments.joinToString("\n") { it.text }
}
private data class GeneratedInsights(val summary: String, val title: String)
data class AskRecordingResult(val answer: String, val sources: List<RecordingFile>)
data class McpTool(val name: String, val description: String, val inputSchema: JSONObject)
data class IdeaShellSaveResult(val noteId: String, val updated: Boolean)
sealed class AiProcessingStage {
    data class Transcribing(val current: Int, val total: Int) : AiProcessingStage()
    data class Summarizing(val current: Int, val total: Int) : AiProcessingStage()
}

class AiProviderException(val userMessage: String) : IOException(userMessage)

/** 仅在请求期间解密凭据；不会记录 API Key、Token 或音频内容。 */
class AiIntegrations(context: Context) {
    private val appContext = context.applicationContext
    private val vault = SecretVault(appContext)
    private val configStore = IntegrationConfigStore(appContext)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    fun isSiliconFlowReady() = vault.isConfigured(SecretVault.SILICONFLOW_API_KEY)
    fun isChatReady() = vault.isConfigured(SecretVault.DEEPSEEK_API_KEY) || isSiliconFlowReady()
    fun isIdeaShellReady() = vault.isConfigured(SecretVault.IDEASHELL_BEARER_TOKEN)

    suspend fun process(
        audio: File,
        knownDurationSeconds: Long,
        reusableSegments: Map<Long, String> = emptyMap(),
        onStage: (AiProcessingStage) -> Unit = {},
        onTranscriptUpdated: (List<AiTranscriptSegment>) -> Unit = {}
    ): AiRecordingResult {
        require(audio.isFile) { "本地录音文件不存在" }
        requireInternetConnection()
        val model = configStore.load().siliconFlowModel
        val multimodal = isMultimodalAudioModel(model)
        val prepared = Mp3AudioChunker.prepare(
            source = audio,
            cacheDirectory = appContext.cacheDir,
            knownDurationSeconds = knownDurationSeconds,
            maxDurationSeconds = if (multimodal) MULTIMODAL_CHUNK_DURATION_SECONDS else ASR_CHUNK_DURATION_SECONDS,
            maxBytes = if (multimodal) MULTIMODAL_CHUNK_SIZE_BYTES else ASR_CHUNK_SIZE_BYTES
        )
        val segments = mutableListOf<AiTranscriptSegment>()
        try {
            prepared.chunks.forEachIndexed { index, chunk ->
                onStage(AiProcessingStage.Transcribing(index + 1, prepared.chunks.size))
                val startKey = chunk.startSeconds.toLong()
                val text = reusableSegments[startKey]?.takeIf(String::isNotBlank) ?: transcribe(chunk.file, model)
                require(text.isNotBlank()) { "第 ${index + 1} 段转录完成，但没有识别到语音" }
                segments += AiTranscriptSegment(chunk.startSeconds, chunk.durationSeconds, text.trim())
                onTranscriptUpdated(segments.toList())
            }
        } finally {
            prepared.close()
        }
        val transcript = segments.joinToString("\n") { it.text }
        require(transcript.isNotBlank()) { "转录完成，但没有识别到语音" }
        val insights = summarizeAndTitle(transcript, onStage)
        return AiRecordingResult(segments, insights.summary, insights.title)
    }

    private fun requireInternetConnection() {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        require(online) {
            "当前没有可用互联网连接。若刚使用 PLAUD Wi-Fi 快传，请等待手机重新连接日常 Wi-Fi 或移动数据。"
        }
    }

    private fun transcribe(audio: File, model: String): String {
        val key = vault.get(SecretVault.SILICONFLOW_API_KEY) ?: error("请先填写硅基流动 API Key")
        if (isMultimodalAudioModel(model)) return transcribeWithMultimodalModel(audio, model, key)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mediaTypeFor(audio))).build()
        val result = executeJson(Request.Builder().url("https://api.siliconflow.cn/v1/audio/transcriptions")
            .header("Authorization", "Bearer $key").post(body).build())
        return result.optString("text").ifBlank { result.optJSONObject("data")?.optString("text").orEmpty() }
    }

    private fun transcribeWithMultimodalModel(audio: File, model: String, key: String): String {
        val mime = mediaTypeFor(audio).toString()
        val encoded = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "audio_url").put(
                "audio_url",
                JSONObject().put("url", "data:$mime;base64,$encoded")
            ))
            .put(JSONObject().put("type", "text").put(
                "text",
                "请忠实转录这段录音，保留原语言和重要数字。只输出转录正文，不要总结、解释或添加前言。"
            ))
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("temperature", 0)
            .put("max_tokens", 16_000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val result = executeJson(Request.Builder().url("https://api.siliconflow.cn/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(jsonType)).build())
        return result.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun isMultimodalAudioModel(model: String): Boolean =
        model.contains("omni", ignoreCase = true)

    private fun summarizeAndTitle(
        transcript: String,
        onStage: (AiProcessingStage) -> Unit
    ): GeneratedInsights {
        val parts = splitTranscript(transcript, MAX_SUMMARY_SOURCE_CHARS)
        if (parts.size == 1) {
            onStage(AiProcessingStage.Summarizing(1, 1))
            return summarizeDirect(parts.first(), transcript)
        }

        val totalCalls = parts.size + 1
        val sectionNotes = parts.mapIndexed { index, part ->
            onStage(AiProcessingStage.Summarizing(index + 1, totalCalls))
            summarizeSection(part, index + 1, parts.size)
        }
        onStage(AiProcessingStage.Summarizing(totalCalls, totalCalls))
        val combined = sectionNotes.mapIndexed { index, note -> "第 ${index + 1} 部分：\n$note" }
            .joinToString("\n\n")
        return summarizeDirect(combined, transcript)
    }

    private fun summarizeSection(transcript: String, index: Int, total: Int): String = completeChat(
        system = "你是长录音整理助手。请忠实提取本段中的主题、事实、重要数字、结论、分歧和待办；不要编造。输出结构清晰的中文要点，不需要生成总标题。",
        user = "这是整段录音的第 $index/$total 部分：\n\n$transcript",
        maxTokens = 2200
    )

    private fun summarizeDirect(source: String, fallbackTranscript: String): GeneratedInsights {
        val config = configStore.load()
        val deepSeekKey = vault.get(SecretVault.DEEPSEEK_API_KEY)
        val siliconKey = vault.get(SecretVault.SILICONFLOW_API_KEY) ?: error("请先填写硅基流动 API Key")
        val useDeepSeek = !deepSeekKey.isNullOrBlank()
        val payload = JSONObject().put("model", if (useDeepSeek) config.deepSeekModel else config.siliconFlowSummaryModel)
            .put("stream", false).put("temperature", 0.2).put("max_tokens", 1800)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你是中文录音整理助手。仅根据转录内容生成标题和总结。标题应具体、自然、便于检索，使用中文，不超过20个汉字，不加书名号或‘标题’前缀。总结包含核心摘要、关键要点和可确认的行动项；不要编造人名、日期或结论。只返回一个合法 JSON 对象，格式为 {\"title\":\"...\",\"summary\":\"...\"}。"))
                .put(JSONObject().put("role", "user").put("content", source)))
        val url = if (useDeepSeek) "https://api.deepseek.com/chat/completions" else "https://api.siliconflow.cn/v1/chat/completions"
        val result = executeJson(Request.Builder().url(url).header("Authorization", "Bearer ${deepSeekKey ?: siliconKey}")
            .post(payload.toString().toRequestBody(jsonType)).build())
        val content = result.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
            .ifBlank { error("总结服务没有返回内容") }
        val structured = extractJsonObject(content)
        val summary = structured?.optString("summary").orEmpty().ifBlank { content }
        val title = cleanGeneratedTitle(structured?.optString("title").orEmpty())
            .ifBlank { fallbackTitle(fallbackTranscript) }
        return GeneratedInsights(summary, title)
    }

    private fun splitTranscript(transcript: String, maxChars: Int): List<String> {
        if (transcript.length <= maxChars) return listOf(transcript)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < transcript.length) {
            val hardEnd = (start + maxChars).coerceAtMost(transcript.length)
            val newline = if (hardEnd < transcript.length) transcript.lastIndexOf('\n', hardEnd) else hardEnd
            val end = newline.takeIf { it >= start + maxChars / 2 } ?: hardEnd
            transcript.substring(start, end).trim().takeIf(String::isNotBlank)?.let(parts::add)
            start = end
            while (start < transcript.length && transcript[start].isWhitespace()) start++
        }
        return parts
    }

    private fun extractJsonObject(content: String): JSONObject? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull()
    }

    private fun cleanGeneratedTitle(raw: String): String = raw
        .trim()
        .removePrefix("标题：")
        .removePrefix("标题:")
        .trim('“', '”', '"', '\'', '《', '》', ' ', '\n', '\r')
        .replace(Regex("\\s+"), " ")
        .take(36)

    private fun fallbackTitle(transcript: String): String = transcript
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
        .trim('“', '”', '"', '\'', ' ', '\n', '\r')
        .take(24)
        .ifBlank { "录音摘要" }

    /**
     * Answers against locally stored transcripts. Only the most relevant recordings are sent to
     * the configured model and their display names are returned for source attribution.
     */
    fun ask(question: String, files: List<RecordingFile>): AskRecordingResult {
        require(question.isNotBlank()) { "请输入一个问题" }
        require(isChatReady()) { "请先在设置中填写硅基流动或 DeepSeek API Key" }

        val searchable = files.mapNotNull { file ->
            TranscriptText.plain(file.transcriptJSON).takeIf(String::isNotBlank)?.let { file to it }
        }
        require(searchable.isNotEmpty()) { "还没有可检索的转录，请先转录一条录音" }

        val terms = searchTerms(question)
        val selected = searchable
            .sortedByDescending { (_, text) -> terms.sumOf { term -> text.countOccurrences(term) } }
            .take(8)

        var remaining = 54_000
        val included = mutableListOf<Pair<RecordingFile, String>>()
        val context = buildString {
            selected.forEachIndexed { index, (file, transcript) ->
                if (remaining <= 0) return@forEachIndexed
                val heading = "[${index + 1}] ${file.name}\n"
                val excerpt = transcript.take((remaining - heading.length).coerceAtLeast(0))
                if (excerpt.isEmpty()) return@forEachIndexed
                included += file to excerpt
                append(heading).append(excerpt).append("\n\n")
                remaining -= heading.length + excerpt.length + 2
            }
        }

        val answer = completeChat(
            system = """你是录音资料库问答助手。只能依据用户提供的录音转录回答；无法确认时明确说不知道。每个事实后用 [1]、[2] 这样的编号标注来源，不要编造来源。先直接回答，再给必要的要点。""",
            user = "问题：$question\n\n录音转录：\n$context",
            maxTokens = 2200
        )
        return AskRecordingResult(answer, included.map { it.first })
    }

    private fun completeChat(system: String, user: String, maxTokens: Int): String {
        val config = configStore.load()
        val deepSeekKey = vault.get(SecretVault.DEEPSEEK_API_KEY)
        val siliconKey = vault.get(SecretVault.SILICONFLOW_API_KEY)
        val useDeepSeek = !deepSeekKey.isNullOrBlank()
        val key = if (useDeepSeek) deepSeekKey else siliconKey
        require(!key.isNullOrBlank()) { "请先在设置中填写硅基流动或 DeepSeek API Key" }

        val payload = JSONObject()
            .put("model", if (useDeepSeek) config.deepSeekModel else config.siliconFlowSummaryModel)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val url = if (useDeepSeek) {
            "https://api.deepseek.com/chat/completions"
        } else {
            "https://api.siliconflow.cn/v1/chat/completions"
        }
        val result = executeJson(Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(jsonType)).build())
        return result.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content").orEmpty()
            .ifBlank { error("问答服务没有返回内容") }
    }

    private fun searchTerms(question: String): Set<String> {
        val normalized = question.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), " ")
        val words = normalized.split(' ').filter { it.length >= 2 }
        val chinese = normalized.filter { it.code in 0x4E00..0x9FFF }
            .windowed(size = 2, step = 1, partialWindows = false)
        return (words + chinese).toSet().ifEmpty { setOf(normalized.trim()) }
    }

    private fun String.countOccurrences(term: String): Int {
        if (term.isBlank()) return 0
        var count = 0
        var offset = 0
        while (true) {
            offset = indexOf(term, offset, ignoreCase = true)
            if (offset < 0) return count
            count++
            offset += term.length
        }
    }

    fun listIdeaShellTools(): List<McpTool> {
        val session = McpSession(openMcp()).apply { initialize() }
        val tools = session.request("tools/list", JSONObject()).optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        return (0 until tools.length()).mapNotNull { index ->
            tools.optJSONObject(index)?.takeIf { it.optString("name").isNotBlank() }?.let {
                McpTool(it.getString("name"), it.optString("description"), it.optJSONObject("inputSchema") ?: JSONObject())
            }
        }
    }

    fun saveToIdeaShell(
        title: String,
        transcript: String,
        summary: String,
        existingNoteId: String? = null
    ): IdeaShellSaveResult {
        val session = McpSession(openMcp()).apply { initialize() }
        val tools = session.request("tools/list", JSONObject()).optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        val updating = !existingNoteId.isNullOrBlank()
        val toolName = if (updating) "note_update" else "note_create"
        val tool = (0 until tools.length()).mapNotNull { tools.optJSONObject(it) }
            .firstOrNull { it.optString("name") == toolName }
            ?: error("闪念贝壳 MCP 未提供 $toolName 工具")

        val cleanTitle = title.lineSequence().firstOrNull().orEmpty().trim()
            .trimEnd('。', '！', '？', '.', '!', '?').take(30).ifBlank { "录音整理" }
        val cleanSummary = summary.trim().take(500)
        val content = "## AI 总结\n\n$cleanSummary\n\n## 转录全文\n\n${transcript.trim()}"
        val args = JSONObject()
            .put("title", cleanTitle)
            .put("content", content)
            .put("summary", cleanSummary)
        if (updating) args.put("note_id", existingNoteId)

        val response = session.request(
            "tools/call",
            JSONObject().put("name", tool.getString("name")).put("arguments", args)
        )
        response.optJSONObject("error")?.let {
            error(it.optString("message", "MCP 调用失败"))
        }
        val result = response.optJSONObject("result") ?: error("闪念贝壳 MCP 未返回执行结果")
        val resultText = mcpResultText(result)
        if (result.optBoolean("isError", false)) {
            error(resultText.ifBlank { "闪念贝壳拒绝了本次保存" })
        }

        val noteId = if (updating) existingNoteId.orEmpty() else extractIdeaShellNoteId(result, resultText)
        check(noteId.isNotBlank()) { "闪念贝壳未返回 note_id，无法确认是否保存成功" }
        return IdeaShellSaveResult(noteId, updating)
    }

    private fun mcpResultText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        return (0 until content.length()).mapNotNull { index ->
            content.optJSONObject(index)?.optString("text")?.takeIf(String::isNotBlank)
        }.joinToString("\n")
    }

    private fun extractIdeaShellNoteId(result: JSONObject, resultText: String): String {
        result.optJSONObject("structuredContent")?.optString("note_id")
            ?.takeIf(String::isNotBlank)?.let { return it }
        runCatching { JSONObject(resultText).optString("note_id") }.getOrNull()
            ?.takeIf(String::isNotBlank)?.let { return it }
        return Regex("(?i)note[_ ]?id[\\s:=\\\"]+([a-z0-9_-]+)")
            .find(resultText)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun openMcp(): Pair<String, String> = configStore.load().validatedIdeaShellUri().toString() to
        (vault.get(SecretVault.IDEASHELL_BEARER_TOKEN) ?: error("请先填写闪念贝壳 MCP Token"))

    private inner class McpSession(private val endpoint: Pair<String, String>) {
        private var sessionId: String? = null
        private var nextId = 1
        fun initialize() {
            request("initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "recorder-hub-android").put("version", "0.1.0")))
            send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").put("params", JSONObject()), false)
        }
        fun request(method: String, params: JSONObject) = send(JSONObject().put("jsonrpc", "2.0")
            .put("id", nextId++).put("method", method).put("params", params))
        private fun send(payload: JSONObject, expectBody: Boolean = true): JSONObject {
            val builder = Request.Builder().url(endpoint.first).header("Authorization", "Bearer ${endpoint.second}")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-03-26")
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            client.newCall(builder.post(payload.toString().toRequestBody(jsonType)).build()).execute().use { response ->
                sessionId = response.header("Mcp-Session-Id") ?: sessionId
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("MCP HTTP ${response.code}: ${raw.take(300)}")
                if (!expectBody || raw.isBlank()) return JSONObject()
                val json = if (response.header("Content-Type").orEmpty().contains("text/event-stream"))
                    raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim().orEmpty() else raw
                return JSONObject(json)
            }
        }
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        val traceId = response.header("x-siliconcloud-trace-id").orEmpty()
        val providerName = if (request.url.host.contains("deepseek", ignoreCase = true)) {
            "DeepSeek"
        } else {
            "硅基流动"
        }
        if (!response.isSuccessful) {
            val payload = runCatching { JSONObject(body) }.getOrNull()
            val providerCode = payload?.optString("code").orEmpty()
            val providerMessage = payload?.optString("message").orEmpty().ifBlank {
                payload?.optJSONObject("error")?.optString("message").orEmpty()
            }
            AppLog.w(
                TAG,
                "Provider request failed: endpoint=${request.url.encodedPath}, http=${response.code}, " +
                    "code=${providerCode.ifBlank { "-" }}, trace=${traceId.ifBlank { "-" }}"
            )
            val rawMessage = body.trim().trim('"').take(240)
            val details = providerMessage.ifBlank { rawMessage }.ifBlank {
                when (response.code) {
                    401 -> "API Key 无效或已经过期，请在设置中重新填写"
                    402 -> "账户余额不足，请先充值后重试"
                    403 -> "当前 API Key 没有调用该模型的权限"
                    413 -> "录音文件超过服务允许的大小"
                    429 -> "请求过于频繁，请稍后重试"
                    in 500..599 -> "服务暂时繁忙，请稍后重试"
                    else -> "服务暂时无法处理该请求"
                }
            }
            val traceSuffix = traceId.takeIf(String::isNotBlank)?.let { "\n追踪 ID：$it" }.orEmpty()
            throw AiProviderException("$providerName 请求失败（HTTP ${response.code}）：$details$traceSuffix")
        }
        AppLog.i(
            TAG,
            "Provider request completed: endpoint=${request.url.encodedPath}, http=${response.code}, " +
                "trace=${traceId.ifBlank { "-" }}"
        )
        if (body.isBlank()) JSONObject() else JSONObject(body)
    }
    private fun mediaTypeFor(file: File) = when (file.extension.lowercase()) {
        "ogg", "opus" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }.toMediaType()

    private companion object {
        const val TAG = "AiIntegrations"
        const val ASR_CHUNK_DURATION_SECONDS = 45L * 60L
        const val ASR_CHUNK_SIZE_BYTES = 45_000_000L
        const val MULTIMODAL_CHUNK_DURATION_SECONDS = 30L * 60L
        const val MULTIMODAL_CHUNK_SIZE_BYTES = 15_000_000L
        const val MAX_SUMMARY_SOURCE_CHARS = 48_000
    }
}
