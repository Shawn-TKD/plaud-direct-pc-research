package com.plaud.template.common

import org.json.JSONArray
import org.json.JSONObject

/** Converts every recorder transcript shape into consistent plain and display text. */
object TranscriptText {
    private data class Segment(val speaker: String, val startMs: Long, val text: String)

    fun plain(json: String?): String = parse(json).joinToString("\n") { it.text }

    fun formatted(json: String?): String = parse(json).joinToString("\n\n") { segment ->
        "${segment.speaker} · ${formatClock(segment.startMs)}\n${segment.text}"
    }

    fun segmentCount(json: String?): Int = parse(json).size

    private fun parse(json: String?): List<Segment> {
        if (json.isNullOrBlank()) return emptyList()
        val segments = runCatching {
            if (json.trimStart().startsWith("[")) {
                JSONArray(json)
            } else {
                val root = JSONObject(json)
                root.optJSONArray("segments")
                    ?: root.optJSONArray("transaction")
                    ?: root.optJSONArray("list")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()
            }
        }.getOrElse { return emptyList() }

        return buildList {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONObject(index) ?: continue
                val text = segment.optString(
                    "content",
                    segment.optString("text", segment.optString("sentence"))
                ).trim()
                if (text.isEmpty()) continue
                val speaker = when {
                    segment.has("speaker_id") -> {
                        val raw = segment.optString("speaker_id")
                        val number = Regex("(\\d+)$").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                        number?.let { "说话人 ${it + 1}" } ?: raw.ifBlank { "说话人" }
                    }
                    else -> "说话人 ${segment.optInt("speaker", 0) + 1}"
                }
                val startMs = when {
                    segment.has("start_time") -> segment.optLong("start_time")
                    segment.has("startTime") -> segment.optLong("startTime")
                    else -> (segment.optDouble("start", 0.0)
                        .takeIf { !it.isNaN() }
                        ?.times(1000))?.toLong() ?: 0L
                }
                add(Segment(speaker, startMs, text))
            }
        }
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}
