package com.plaud.template.hub.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.plaud.template.PlaudTemplateApp
import com.plaud.template.hub.device.dingtalk.A1Credentials
import com.plaud.template.hub.device.dingtalk.A1RemoteRecording
import com.plaud.template.hub.device.dingtalk.DingTalkA1Client
import com.plaud.template.hub.device.feishu.D3200Client
import com.plaud.template.hub.security.SecretVault
import com.plaud.template.managers.SyncManager
import com.plaud.template.models.RecordingFile
import com.plaud.template.storage.RecordingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

enum class A1TransferPhase { DOWNLOADING, COMPLETED, FAILED }

data class A1TransferStatus(
    val fid: Long,
    val phase: A1TransferPhase,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null
)

data class A1ImportResult(
    val importedFids: List<Long>,
    val failures: Map<Long, String>
)

/**
 * Application-scoped owner for recorder sessions that use native Android BLE.
 *
 * Activities are views over these sessions, not their owners. Keeping one client per protocol
 * lets A1 and D3200 remain connected at the same time while the PLAUD SDK continues to own its
 * single active PLAUD session independently.
 */
class PeripheralSessionHub(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dingTalkA1Client: DingTalkA1Client by lazy { DingTalkA1Client(appContext) }
    val d3200Client: D3200Client by lazy { D3200Client(appContext) }

    private val a1ImportMutex = Mutex()
    private val a1ReconnectMutex = Mutex()
    private val reconnectRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val a1ImportJobs = mutableMapOf<Set<Long>, Deferred<A1ImportResult>>()
    private val a1Prefs by lazy {
        appContext.getSharedPreferences("dingtalk_a1_config", Context.MODE_PRIVATE)
    }
    private val secretVault by lazy { SecretVault(appContext) }

    private val _dingTalkCatalog = MutableStateFlow<List<A1RemoteRecording>>(emptyList())
    val dingTalkCatalog: StateFlow<List<A1RemoteRecording>> = _dingTalkCatalog.asStateFlow()
    private var dingTalkCatalogDid: String? = null
    private var connectedDingTalkDid: String? = null

    private val _dingTalkTransfers = MutableStateFlow<Map<Long, A1TransferStatus>>(emptyMap())
    val dingTalkTransfers: StateFlow<Map<Long, A1TransferStatus>> = _dingTalkTransfers.asStateFlow()

    init {
        RecordingStore.mergeDingTalkLiveRows(DINGTALK_DEVICE_SN, DINGTALK_LIVE_DEVICE_SN)
        appScope.launch {
            dingTalkA1Client.connectionState.collect { connected ->
                if (!connected) connectedDingTalkDid = null
            }
        }
        appScope.launch {
            dingTalkA1Client.completedCaptures.collect { capture ->
                val label = when (capture.recordingType) {
                    1 -> "A1 语音备忘录"
                    2 -> "A1 实时录音"
                    else -> "A1 实时音频"
                }
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(Date(capture.fid * 1000))
                RecordingStore.addFiles(listOf(RecordingFile(
                    id = "a1-${capture.fid}",
                    sessionId = capture.fid,
                    // The live stream and the device-directory row share the same fid and are
                    // two delivery paths for one recording. Use one canonical identity so a
                    // later catalog refresh upgrades this row instead of duplicating it.
                    deviceSN = DINGTALK_DEVICE_SN,
                    name = "$label $time",
                    duration = capture.durationSeconds.roundToLong(),
                    createdAt = capture.fid * 1000,
                    syncedAt = System.currentTimeMillis(),
                    localPath = capture.localFile.absolutePath
                )))
                (appContext as? PlaudTemplateApp)?.syncManager?.refreshFromStore()
            }
        }
    }

    /** Read the A1 directory and publish remote-only rows into the shared Files store. */
    suspend fun refreshDingTalkCatalog(): List<A1RemoteRecording> {
        check(dingTalkA1Client.connectionState.value) { "钉钉 A1 尚未连接" }
        val credentials = savedDingTalkCredentials()
            ?: error("请先在钉钉 A1 页面保存 DID、corpId 和 deviceSecret")
        val recordings = dingTalkA1Client.listRecordings(credentials)
        registerDingTalkCatalog(recordings, credentials.did)
        return recordings
    }

    /** Remember the already-authenticated recorder so a process restart can reconnect directly. */
    fun rememberDingTalkAddress(address: String) {
        a1Prefs.edit().putString(KEY_DINGTALK_ADDRESS, address).apply()
    }

    fun savedDingTalkAddress(): String? =
        a1Prefs.getString(KEY_DINGTALK_ADDRESS, null)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * The single entry point for opening an authenticated A1 GATT session.
     *
     * Both foreground/manual connection and process auto-reconnect use this mutex. The state is
     * deliberately checked after taking the lock, so a slower scan cannot close a connection
     * that the background service completed in the meantime.
     */
    suspend fun connectDingTalk(address: String, credentials: A1Credentials) {
        a1ReconnectMutex.withLock {
            val saved = savedDingTalkCredentials()
            if (dingTalkA1Client.connectionState.value &&
                connectedDingTalkDid == credentials.did && saved == credentials
            ) return@withLock
            if (dingTalkA1Client.connectionState.value) dingTalkA1Client.close()
            dingTalkA1Client.connect(address, credentials)
            connectedDingTalkDid = credentials.did
            secretVault.put(SecretVault.DINGTALK_DEVICE_SECRET, credentials.deviceSecret)
            a1Prefs.edit()
                .putString("did", credentials.did)
                .putString("corp_id", credentials.corpId)
                .apply()
            rememberDingTalkAddress(address)
            if (dingTalkCatalogDid != null && dingTalkCatalogDid != credentials.did) {
                dingTalkCatalogDid = null
                _dingTalkCatalog.value = emptyList()
            }
        }
    }

    /**
     * Restore the owner's A1 session without scanning or reopening the credential form.
     *
     * A direct GATT reconnect is intentionally used here: it is quick when the recorder is awake,
     * does not compete with the other device scanners, and simply ends if the A1 is unavailable.
     */
    fun attemptDingTalkReconnect() {
        if (!reconnectRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                if (dingTalkA1Client.connectionState.value || !hasBluetoothPermission()) return@launch
                val address = a1Prefs.getString(KEY_DINGTALK_ADDRESS, null)?.trim().orEmpty()
                val credentials = savedDingTalkCredentials()
                if (address.isBlank() || credentials == null) return@launch
                try {
                    connectDingTalk(address, credentials)
                    refreshDingTalkCatalog()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* The foreground service retries while active. */ }
            } finally { reconnectRunning.set(false) }
        }
    }

    /**
     * Make the authenticated A1 catalog part of the product-wide Files model. Pending recordings
     * are immediately visible; downloaded copies are retained even after deletion on the device.
     */
    fun registerDingTalkCatalog(recordings: List<A1RemoteRecording>, ownerDid: String? = null) {
        dingTalkCatalogDid = ownerDid ?: savedDingTalkCredentials()?.did
        _dingTalkCatalog.value = recordings.sortedByDescending { it.fid }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val rows = recordings.map { item ->
            RecordingFile(
                id = "a1-${item.fid}",
                sessionId = item.fid,
                deviceSN = DINGTALK_DEVICE_SN,
                name = "A1 录音 ${formatter.format(Date(item.fid * 1000))}",
                duration = item.durationSeconds.toLong(),
                createdAt = item.fid * 1000,
                syncedAt = null,
                localPath = null
            )
        }
        RecordingStore.reconcileDeviceCatalog(DINGTALK_DEVICE_SN, rows)
        SyncManager.shared.refreshFromStore()
    }

    /**
     * Application-scoped import entry point. The BLE transfer keeps running when the user leaves
     * the A1/Files screen, matching PLAUD's manager-owned sync behavior.
     */
    fun startDingTalkImport(fids: Collection<Long>): Deferred<A1ImportResult> {
        val key = fids.toSet()
        synchronized(a1ImportJobs) {
            a1ImportJobs[key]?.let { return it }
            val job = appScope.async { importDingTalkRecordings(key) }
            a1ImportJobs[key] = job
            job.invokeOnCompletion {
                synchronized(a1ImportJobs) {
                    if (a1ImportJobs[key] === job) a1ImportJobs.remove(key)
                }
            }
            return job
        }
    }

    /** Download selected A1 recordings into the same playable/transcribable Files store as PLAUD. */
    suspend fun importDingTalkRecordings(fids: Collection<Long>): A1ImportResult =
        a1ImportMutex.withLock {
            check(dingTalkA1Client.connectionState.value) { "钉钉 A1 尚未连接" }
            val credentials = savedDingTalkCredentials()
                ?: error("钉钉 A1 凭据不可用，请重新连接一次")
            var catalog = _dingTalkCatalog.value
            if (dingTalkCatalogDid != credentials.did || catalog.isEmpty() ||
                fids.any { fid -> catalog.none { it.fid == fid } }
            ) {
                catalog = refreshDingTalkCatalog()
            }
            val requested = fids.toSet()
            val downloaded = RecordingStore.allFiles.asSequence()
                .filter { it.deviceSN == DINGTALK_DEVICE_SN && it.sessionId in requested }
                .filter { it.localPath?.let { path -> File(path).isFile } == true }
                .mapTo(linkedSetOf()) { it.sessionId }
            val targets = catalog.filter { it.fid in requested && it.fid !in downloaded }
            check(targets.isNotEmpty() || downloaded.isNotEmpty()) {
                "A1 中已找不到所选录音，请刷新后重试"
            }
            if (targets.isEmpty()) return@withLock A1ImportResult(downloaded.toList(), emptyMap())

            val owner = A1_SYNC_OWNER
            val ownsGlobalBanner = SyncManager.shared.beginExternalSync(owner, targets.size)
            val imported = downloaded.toMutableList()
            val failures = linkedMapOf<Long, String>()
            val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            var cancelledTransfer = false

            try {
                targets.forEachIndexed { index, item ->
                    val displayName = "A1 录音 ${formatter.format(Date(item.fid * 1000))}"
                    updateA1Transfer(A1TransferStatus(item.fid, A1TransferPhase.DOWNLOADING))
                    try {
                        val destination = File(appContext.filesDir, "audio/a1-${item.fid}.ogg")
                        dingTalkA1Client.download(credentials, item, destination) { received, total ->
                            updateA1Transfer(
                                A1TransferStatus(
                                    fid = item.fid,
                                    phase = A1TransferPhase.DOWNLOADING,
                                    receivedBytes = received,
                                    totalBytes = total
                                )
                            )
                            if (ownsGlobalBanner) {
                                SyncManager.shared.updateExternalSync(
                                    owner = owner,
                                    totalFiles = targets.size,
                                    completedFiles = index,
                                    currentFileName = displayName,
                                    receivedBytes = received,
                                    totalBytes = total
                                )
                            }
                        }
                        RecordingStore.addFiles(listOf(RecordingFile(
                            id = "a1-${item.fid}",
                            sessionId = item.fid,
                            deviceSN = DINGTALK_DEVICE_SN,
                            name = displayName,
                            duration = item.durationSeconds.toLong(),
                            createdAt = item.fid * 1000,
                            syncedAt = System.currentTimeMillis(),
                            localPath = destination.absolutePath
                        )))
                        SyncManager.shared.refreshFromStore()
                        imported += item.fid
                        updateA1Transfer(A1TransferStatus(item.fid, A1TransferPhase.COMPLETED))
                    } catch (cancelled: CancellationException) {
                        cancelledTransfer = true
                        updateA1Transfer(
                            A1TransferStatus(item.fid, A1TransferPhase.FAILED, message = "导入已取消")
                        )
                        throw cancelled
                    } catch (error: Exception) {
                        val message = error.message ?: "下载失败"
                        failures[item.fid] = message
                        updateA1Transfer(
                            A1TransferStatus(item.fid, A1TransferPhase.FAILED, message = message)
                        )
                    }
                }
            } finally {
                if (ownsGlobalBanner) {
                    val error = when {
                        cancelledTransfer -> "A1 导入已取消"
                        failures.isNotEmpty() ->
                            "A1 已导入 ${imported.size} 条，${failures.size} 条失败"
                        else -> null
                    }
                    SyncManager.shared.finishExternalSync(owner, error)
                }
            }
            A1ImportResult(imported, failures)
        }

    /** Delete only the A1 device copy; a downloaded local Files copy is deliberately retained. */
    suspend fun deleteDingTalkRecording(fid: Long) {
        val credentials = savedDingTalkCredentials()
            ?: error("钉钉 A1 凭据不可用，请重新连接一次")
        val catalog = if (dingTalkCatalogDid == credentials.did &&
            _dingTalkCatalog.value.any { it.fid == fid }
        ) {
            _dingTalkCatalog.value
        } else {
            refreshDingTalkCatalog()
        }
        val item = catalog.firstOrNull { it.fid == fid }
            ?: error("A1 中已找不到这条录音")
        dingTalkA1Client.deleteRecording(credentials, item)
        registerDingTalkCatalog(catalog.filterNot { it.fid == fid }, credentials.did)
    }

    private fun savedDingTalkCredentials(): A1Credentials? {
        val did = a1Prefs.getString("did", null)?.trim().orEmpty()
        val corpId = a1Prefs.getString("corp_id", null)?.trim().orEmpty()
        val secret = secretVault.get(SecretVault.DINGTALK_DEVICE_SECRET)?.trim().orEmpty()
        return if (did.isNotBlank() && corpId.isNotBlank() && secret.length >= 16) {
            A1Credentials(did, corpId, secret)
        } else null
    }

    private fun updateA1Transfer(status: A1TransferStatus) {
        _dingTalkTransfers.value = _dingTalkTransfers.value + (status.fid to status)
    }

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val DINGTALK_DEVICE_SN = "DINGTALK_A1"
        const val DINGTALK_LIVE_DEVICE_SN = "DINGTALK_A1_LIVE"
        private const val A1_SYNC_OWNER = "dingtalk-a1"
        private const val KEY_DINGTALK_ADDRESS = "last_device_address"
    }
}
