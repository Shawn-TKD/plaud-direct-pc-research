package com.plaud.template.hub.integrations

import android.content.Context
import com.plaud.template.common.AppLog
import com.plaud.template.models.RecordingFile
import com.plaud.template.storage.RecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed class AiProcessingState {
    object Idle : AiProcessingState()
    data class Running(val fileId: String, val status: String) : AiProcessingState()
    data class Completed(val fileId: String) : AiProcessingState()
    data class Failed(val fileId: String, val message: String) : AiProcessingState()
}

data class AiBatchFailure(val fileId: String, val fileName: String, val message: String)

sealed class AiBatchState {
    object Idle : AiBatchState()
    data class Running(
        val current: Int,
        val total: Int,
        val fileId: String,
        val fileName: String,
        val status: String,
        val succeeded: Int,
        val failed: Int
    ) : AiBatchState()
    data class Completed(
        val total: Int,
        val succeeded: Int,
        val failures: List<AiBatchFailure>
    ) : AiBatchState()
}

/**
 * Owns the transcription job outside any Activity lifecycle. A screen rotation or temporary
 * navigation therefore cannot cancel the HTTP call or make a destroyed Activity show a dialog.
 */
class AiProcessingManager(context: Context) {
    private val integrations = AiIntegrations(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<AiProcessingState>(AiProcessingState.Idle)
    val state: StateFlow<AiProcessingState> = _state.asStateFlow()
    private val _batchState = MutableStateFlow<AiBatchState>(AiBatchState.Idle)
    val batchState: StateFlow<AiBatchState> = _batchState.asStateFlow()

    fun isSiliconFlowReady(): Boolean = integrations.isSiliconFlowReady()

    @Synchronized
    fun start(file: RecordingFile): Boolean {
        if (_state.value is AiProcessingState.Running || _batchState.value is AiBatchState.Running) return false

        val path = file.localPath
        val audio = path?.let(::File)
        if (audio == null || !audio.isFile) {
            _state.value = AiProcessingState.Failed(file.id, "本地音频文件不存在，请重新导入录音")
            return true
        }
        _state.value = AiProcessingState.Running(file.id, "正在准备音频…")
        scope.launch {
            try {
                processFile(file) { status ->
                    _state.value = AiProcessingState.Running(file.id, status)
                }
                _state.value = AiProcessingState.Completed(file.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLog.e(TAG, "AI processing failed for fileId=${file.id}: ${error.javaClass.simpleName}", error)
                _state.value = AiProcessingState.Failed(file.id, friendlyMessage(error))
            }
        }
        return true
    }

    /** Process every downloaded recording that is still missing a transcript or summary. */
    @Synchronized
    fun startBatch(files: List<RecordingFile>): Boolean {
        if (_state.value is AiProcessingState.Running || _batchState.value is AiBatchState.Running) return false
        val candidates = files.distinctBy { it.id }
            .filter { file ->
                file.localPath?.let { File(it).isFile } == true &&
                    (file.transcriptJSON.isNullOrBlank() || file.summaryText.isNullOrBlank())
            }
            .sortedByDescending { it.createdAt }
        if (candidates.isEmpty()) return false

        _batchState.value = AiBatchState.Running(
            current = 1,
            total = candidates.size,
            fileId = candidates.first().id,
            fileName = candidates.first().name,
            status = "正在准备音频…",
            succeeded = 0,
            failed = 0
        )
        scope.launch {
            val failures = mutableListOf<AiBatchFailure>()
            var succeeded = 0
            candidates.forEachIndexed { index, queued ->
                val file = RecordingStore.allFiles.firstOrNull { it.id == queued.id } ?: queued
                val publish: (String) -> Unit = { status ->
                    _state.value = AiProcessingState.Running(file.id, status)
                    _batchState.value = AiBatchState.Running(
                        current = index + 1,
                        total = candidates.size,
                        fileId = file.id,
                        fileName = file.name,
                        status = status,
                        succeeded = succeeded,
                        failed = failures.size
                    )
                }
                try {
                    publish("正在准备音频…")
                    processFile(file, publish)
                    succeeded++
                    _state.value = AiProcessingState.Completed(file.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val message = friendlyMessage(error)
                    failures += AiBatchFailure(file.id, file.name, message)
                    AppLog.e(
                        TAG,
                        "Batch AI processing failed for fileId=${file.id}: ${error.javaClass.simpleName}",
                        error
                    )
                    _state.value = AiProcessingState.Failed(file.id, message)
                }
            }
            _batchState.value = AiBatchState.Completed(candidates.size, succeeded, failures.toList())
        }
        return true
    }

    fun consumeBatchTerminal() {
        if (_batchState.value is AiBatchState.Completed) _batchState.value = AiBatchState.Idle
    }

    private suspend fun processFile(file: RecordingFile, onStatus: (String) -> Unit) {
        val audio = file.localPath?.let(::File)?.takeIf { it.isFile }
            ?: error("本地音频文件不存在，请重新导入录音")
        val result = integrations.process(
            audio = audio,
            knownDurationSeconds = file.duration,
            reusableSegments = reusableSegments(file.transcriptJSON),
            onStage = { stage ->
                onStatus(when (stage) {
                    is AiProcessingStage.Transcribing ->
                        if (stage.total == 1) "正在上传并转录…"
                        else "正在转录第 ${stage.current}/${stage.total} 段…"
                    is AiProcessingStage.Summarizing ->
                        if (stage.total == 1) "正在生成标题和总结…"
                        else "正在整理长录音 ${stage.current}/${stage.total}…"
                })
            },
            onTranscriptUpdated = { segments ->
                RecordingStore.updateTranscript(file.id, segmentsToJson(segments))
            }
        )
        val transcriptJson = segmentsToJson(result.segments)
        check(RecordingStore.updateAiResult(file.id, transcriptJson, result.summary, result.title)) {
            "录音已在处理期间被删除，结果没有保存"
        }
    }

    fun consumeTerminal(fileId: String) {
        when (val current = _state.value) {
            is AiProcessingState.Completed -> if (current.fileId == fileId) _state.value = AiProcessingState.Idle
            is AiProcessingState.Failed -> if (current.fileId == fileId) _state.value = AiProcessingState.Idle
            else -> Unit
        }
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is UnknownHostException, is ConnectException ->
            "无法连接硅基流动，请确认手机已退出录音设备热点并恢复互联网连接。"
        is SocketTimeoutException ->
            "服务器处理超时。录音越长需要的时间越久，请保持网络稳定后重试。"
        is AiProviderException -> error.userMessage
        else -> error.message?.takeIf(String::isNotBlank) ?: "处理录音时发生未知错误"
    }

    private fun segmentsToJson(segments: List<AiTranscriptSegment>): String {
        val output = JSONArray()
        segments.forEach { segment ->
            output.put(JSONObject()
                .put("speaker", 0)
                .put("start", segment.startSeconds)
                .put("duration", segment.durationSeconds)
                .put("text", segment.text))
        }
        return output.toString()
    }

    /** Reuses already persisted chunks after a network or summary failure. */
    private fun reusableSegments(json: String?): Map<Long, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isNotBlank()) put(item.optDouble("start", 0.0).toLong(), text)
            }
        }
    }

    private companion object {
        const val TAG = "AiProcessing"
    }
}
