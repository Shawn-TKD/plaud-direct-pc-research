package com.plaud.template.hub.device

import com.plaud.template.hub.model.DeviceVendor
import com.plaud.template.hub.model.HubDevice
import com.plaud.template.hub.model.HubRecording
import com.plaud.template.hub.model.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Common contract used by the UI and sync scheduler for every recorder family. */
interface RecorderDeviceAdapter {
    val vendor: DeviceVendor
    val devices: StateFlow<List<HubDevice>>
    val transferProgress: Flow<TransferProgress>

    suspend fun scan(timeoutMs: Long = 12_000)
    suspend fun connect(stableId: String)
    suspend fun disconnect(stableId: String)
    suspend fun listRecordings(stableId: String): List<HubRecording>
    suspend fun download(recording: HubRecording, destination: File): File
    suspend fun deleteRemote(recording: HubRecording)
}

enum class AdapterReadiness {
    VERIFIED, PARTIAL, WAITING_FOR_PROTOCOL
}

data class AdapterStatus(
    val vendor: DeviceVendor,
    val readiness: AdapterReadiness,
    val note: String
)
