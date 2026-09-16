package com.plaud.template

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.plaud.template.common.PcBootstrapExporter
import com.plaud.template.hub.automation.AutomationPolicyStore
import com.plaud.template.hub.device.PeripheralSessionHub
import com.plaud.template.hub.integrations.AiProcessingManager
import com.plaud.template.hub.security.SecretVault
import com.plaud.template.hub.service.BleSyncService
import com.plaud.template.managers.DeviceManager
import com.plaud.template.managers.DeviceManagerProtocol
import com.plaud.template.managers.RecordingManager
import com.plaud.template.managers.RecordingManagerProtocol
import com.plaud.template.managers.SyncManager
import com.plaud.template.managers.SyncManagerProtocol
import com.plaud.template.managers.mock.MockDeviceManager
import com.plaud.template.managers.mock.MockRecordingManager
import com.plaud.template.managers.mock.MockSyncManager
import com.plaud.template.storage.RecordingStore

/**
 * Application entry point
 * Initializes RecordingStore and the manager singletons
 */
class PlaudTemplateApp : Application() {

    companion object {
        /** Set to true to use the mock managers, enabling UI development without a real device */
        const val USE_MOCK = false

        lateinit var instance: PlaudTemplateApp
            private set
    }

    // Manager instances (switch between real / mock based on USE_MOCK)
    val deviceManager: DeviceManagerProtocol by lazy {
        if (USE_MOCK) MockDeviceManager() else DeviceManager.shared.also { it.setContext(this) }
    }
    val recordingManager: RecordingManagerProtocol by lazy {
        if (USE_MOCK) MockRecordingManager() else RecordingManager.shared
    }
    val syncManager: SyncManagerProtocol by lazy {
        if (USE_MOCK) MockSyncManager() else SyncManager.shared
    }
    val aiProcessingManager: AiProcessingManager by lazy { AiProcessingManager(this) }
    val peripheralSessionHub: PeripheralSessionHub by lazy { PeripheralSessionHub(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RecordingStore.init(this)
        seedBuildDefaults()
        // The foreground service owns process priority; individual Activities only render and
        // control the application-scoped BLE sessions.
        peripheralSessionHub
        ContextCompat.startForegroundService(this, Intent(this, BleSyncService::class.java))
        if (BuildConfig.DEBUG) {
            Thread({ PcBootstrapExporter.exportIfRequested(this) }, "pc-bootstrap-export").start()
        }
        registerForegroundReconnect()
    }

    /**
     * Provision local-build defaults once, then let the encrypted user value take precedence.
     * The secret is never logged or shown in the settings UI.
     */
    private fun seedBuildDefaults() {
        val vault = SecretVault(this)

        val defaultSiliconFlowKey = BuildConfig.SILICONFLOW_API_KEY.trim()
        if (defaultSiliconFlowKey.isNotBlank() &&
            !vault.isConfigured(SecretVault.SILICONFLOW_API_KEY)
        ) {
            vault.put(SecretVault.SILICONFLOW_API_KEY, defaultSiliconFlowKey)
        }

        val defaultIdeaShellToken = BuildConfig.IDEASHELL_BEARER_TOKEN.trim()
        if (defaultIdeaShellToken.isNotBlank() &&
            !vault.isConfigured(SecretVault.IDEASHELL_BEARER_TOKEN)
        ) {
            vault.put(SecretVault.IDEASHELL_BEARER_TOKEN, defaultIdeaShellToken)
        }
    }

    /**
     * App-wide foreground reconnect trigger (mirrors iOS sceneDidBecomeActive): whenever the app
     * returns to the foreground from ANY activity — not just MainActivity — try to reconnect the
     * last device after a short Bluetooth-stack settle delay, guarded by checks in attemptReconnect().
     */
    private fun registerForegroundReconnect() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
            private var startedCount = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                val wasBackground = startedCount == 0
                startedCount++
                if (!wasBackground || USE_MOCK) return
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (RecordingStore.lastConnectedDeviceSN != null &&
                        RecordingStore.userId != null
                    ) {
                        deviceManager.attemptReconnect()
                    }
                    peripheralSessionHub.attemptDingTalkReconnect()
                }, 500)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
            }
        })
    }
}

/** No-op base so callers override only the callbacks they need. */
open class ActivityLifecycleCallbacksAdapter : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {}
    override fun onActivityResumed(activity: android.app.Activity) {}
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {}
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
