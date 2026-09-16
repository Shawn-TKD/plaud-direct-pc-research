package com.plaud.template.hub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Owns long-lived BLE sessions and transfer jobs. Protocol adapters will attach to this service;
 * Android is allowed to keep it alive because the connection is visible to the user.
 */
class BleSyncService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音设备连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持录音设备连接并同步新文件" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification(emptyList()))

        val app = application as PlaudTemplateApp
        serviceScope.launch {
            while (isActive) {
                app.peripheralSessionHub.attemptDingTalkReconnect()
                delay(30_000)
            }
        }
        serviceScope.launch {
            combine(
                app.deviceManager.connectedDevice,
                app.peripheralSessionHub.dingTalkA1Client.connectionState,
                app.peripheralSessionHub.d3200Client.connectionState
            ) { plaud, dingTalk, d3200 ->
                buildList {
                    if (plaud != null) add("PLAUD")
                    if (dingTalk) add("钉钉 A1")
                    if (d3200) add("飞书录音豆")
                }
            }.collect { connected ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(connected))
            }
        }
    }

    private fun buildNotification(connected: List<String>) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("Memo Hub 正在运行")
            .setContentText(
                if (connected.isEmpty()) "等待录音设备或同步任务"
                else "已连接：${connected.joinToString("、")}"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recorder_ble_sync"
        private const val NOTIFICATION_ID = 4101
    }
}
