package com.plaud.template.models

data class SyncProgress(
    val totalFiles: Int,
    val syncedFiles: Int,
    val currentFileName: String? = null,
    val fileProgress: Float = 0f,
    val bytesPerSecond: Long = 0L,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    val progressFraction: Float
        get() = if (totalFiles > 0) {
            (syncedFiles.toFloat() + fileProgress) / totalFiles
        } else {
            0f
        }

    val speedText: String
        get() {
            val mbps = bytesPerSecond / (1024f * 1024f)
            return if (mbps >= 1f) {
                String.format("%.1f MB/s", mbps)
            } else {
                val kbps = bytesPerSecond / 1024f
                String.format("%.0f KB/s", kbps)
            }
        }

    val dataText: String
        get() = if (totalBytes > 0) {
            "${formatBytes(transferredBytes.coerceAtMost(totalBytes))} / ${formatBytes(totalBytes)}"
        } else ""

    val remainingText: String
        get() {
            if (bytesPerSecond <= 0 || totalBytes <= transferredBytes) return ""
            val seconds = ((totalBytes - transferredBytes) / bytesPerSecond).coerceAtLeast(1)
            return when {
                seconds < 60 -> "约 ${seconds} 秒"
                seconds < 3600 -> "约 ${(seconds + 59) / 60} 分钟"
                else -> "约 ${seconds / 3600} 小时 ${(seconds % 3600 + 59) / 60} 分钟"
            }
        }

    val transferDetailText: String
        get() = listOfNotNull(
            speedText.takeIf { bytesPerSecond > 0 },
            dataText.takeIf(String::isNotBlank),
            remainingText.takeIf(String::isNotBlank)
        ).joinToString(" · ")

    private fun formatBytes(value: Long): String {
        val mb = value / (1024f * 1024f)
        return if (mb >= 1024f) String.format("%.2f GB", mb / 1024f)
        else String.format("%.1f MB", mb)
    }
}
