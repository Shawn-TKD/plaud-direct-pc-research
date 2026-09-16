package com.plaud.template.hub.model

enum class DeviceVendor(val displayName: String) {
    DINGTALK_A1("DingTalk A1"),
    FEISHU_RECORDER("Feishu Recorder"),
    PLAUD_NOTEPIN("Plaud NotePin")
}

enum class HubConnectionState {
    DISCONNECTED, SCANNING, CONNECTING, AUTHENTICATING, CONNECTED, SYNCING, ERROR
}

data class HubDevice(
    val stableId: String,
    val vendor: DeviceVendor,
    val displayName: String,
    val connectionState: HubConnectionState = HubConnectionState.DISCONNECTED,
    val batteryPercent: Int? = null,
    val storageUsedBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val firmwareVersion: String? = null,
    val lastError: String? = null
)

data class HubRecording(
    val id: String,
    val deviceId: String,
    val vendor: DeviceVendor,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val remoteName: String,
    val localPath: String? = null,
    val transcript: String? = null,
    val summary: String? = null,
    val marked: Boolean = false,
    val downloaded: Boolean = localPath != null
)

data class TransferProgress(
    val recordingId: String,
    val bytesReceived: Long,
    val totalBytes: Long?
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { bytesReceived.toFloat() / it }
}
