package com.plaud.template.hub.device

import com.plaud.template.hub.model.DeviceVendor

/** Public readiness of each hardware adapter shown by Recorder Hub. */
object AdapterCatalog {
    val status = listOf(
        AdapterStatus(
            DeviceVendor.PLAUD_NOTEPIN,
            AdapterReadiness.VERIFIED,
            "PLAUD SDK BLE connection, listing, download and Ogg export"
        ),
        AdapterStatus(
            DeviceVendor.DINGTALK_A1,
            AdapterReadiness.PARTIAL,
            "Owner-credential BLE authentication, long-recording listing and download"
        ),
        AdapterStatus(
            DeviceVendor.FEISHU_RECORDER,
            AdapterReadiness.VERIFIED,
            "Native D3200 BLE, status, recording control, listing, ECDH/AES decryption and delete"
        )
    )
}
