package com.plaud.template.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.plaud.template.models.RecordingFile
import java.io.File
import java.io.FileOutputStream

object RecordingStore {

    private const val PREFS_NAME = "plaud_template_prefs"
    private const val KEY_LAST_CONNECTED_SN = "last_connected_device_sn"
    private const val KEY_PAIRED_SNS = "paired_device_sns"
    private const val KEY_PAIRED_NAMES = "paired_device_names"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTO_SYNC = "is_auto_sync_enabled"
    private const val KEY_FAST_TRANSFER_HIDE = "fast_transfer_never_show"
    private const val RECORDINGS_FILE = "recordings.json"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- SharedPreferences ---

    /** Active device SN (the one currently selected / to auto-reconnect). */
    var lastConnectedDeviceSN: String?
        get() = prefs.getString(KEY_LAST_CONNECTED_SN, null)
        set(value) = prefs.edit().putString(KEY_LAST_CONNECTED_SN, value).apply()

    // --- Paired devices (multi-device support, mirrors iOS) ---

    /** Serial numbers of all paired devices. */
    val pairedDeviceSNs: List<String>
        get() = prefs.getString(KEY_PAIRED_SNS, null)?.let {
            runCatching { gson.fromJson(it, Array<String>::class.java).toList() }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun savePairedDeviceSNs(value: List<String>) =
        prefs.edit().putString(KEY_PAIRED_SNS, gson.toJson(value)).apply()

    /** [SN: display name] cache for paired devices. */
    private val pairedDeviceNames: Map<String, String>
        get() = prefs.getString(KEY_PAIRED_NAMES, null)?.let {
            val type = object : TypeToken<Map<String, String>>() {}.type
            runCatching { gson.fromJson<Map<String, String>>(it, type) }.getOrNull()
        } ?: emptyMap()

    private fun savePairedDeviceNames(value: Map<String, String>) =
        prefs.edit().putString(KEY_PAIRED_NAMES, gson.toJson(value)).apply()

    /** Add a paired device and make it the active device. */
    fun addPairedDevice(sn: String, name: String) {
        val sns = pairedDeviceSNs.toMutableList()
        if (!sns.contains(sn)) sns.add(sn)
        savePairedDeviceSNs(sns)
        savePairedDeviceNames(pairedDeviceNames.toMutableMap().apply { put(sn, name) })
        lastConnectedDeviceSN = sn
    }

    /** Remove a paired device; if it was active, fall back to the first remaining one. */
    fun removePairedDevice(sn: String) {
        val sns = pairedDeviceSNs.toMutableList().apply { remove(sn) }
        savePairedDeviceSNs(sns)
        savePairedDeviceNames(pairedDeviceNames.toMutableMap().apply { remove(sn) })
        if (lastConnectedDeviceSN == sn) lastConnectedDeviceSN = sns.firstOrNull()
    }

    /** Display name for a paired device SN (falls back to the SN itself). */
    fun deviceName(sn: String): String = pairedDeviceNames[sn] ?: sn

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    /** Runtime-entered User Access Token (Settings → Replace). Overrides the build-time value. */
    var userAccessTokenOverride: String?
        get() = prefs.getString("user_access_token_override", null)
        set(value) = prefs.edit().putString("user_access_token_override", value).apply()

    /** Active token = runtime override (account switch / renewal) ?: build-injected default. */
    val activeUserAccessToken: String
        get() = userAccessTokenOverride ?: com.plaud.template.BuildConfig.USER_ACCESS_TOKEN

    /** Runtime-entered transcription API key (Settings → API Key). Overrides the build-time value. */
    var apiKeyOverride: String?
        get() = prefs.getString("api_key_override", null)
        set(value) = prefs.edit().putString("api_key_override", value).apply()

    /** Active API key = runtime override ?: build-injected default. Read per-request, no restart needed. */
    val activeApiKey: String
        get() = apiKeyOverride ?: com.plaud.template.BuildConfig.PLAUD_API_KEY

    /**
     * X-Client-Id for transcription: derived from the active userAccessToken's JWT `client_id`
     * claim so it always matches the account/environment in use; build-injected value as fallback.
     */
    val activeClientId: String
        get() = com.plaud.template.common.JwtUtils.parse(activeUserAccessToken)?.clientId
            ?.takeIf { it.isNotBlank() }
            ?: com.plaud.template.BuildConfig.PLAUD_CLIENT_ID

    const val PROD_SERVER_DOMAIN = "platform-us.plaud.ai"
    const val PRE_SERVER_DOMAIN = "platform-us-pre.plaud.ai"
    const val TEST_SERVER_DOMAIN = "platform-test.plaud.ai"

    /**
     * Selectable environments in display order — single source for the Settings picker and the
     * card label, so adding an environment never leaves the two out of sync.
     */
    val serverEnvironments: List<Pair<String, String>> = listOf(
        "Production" to PROD_SERVER_DOMAIN,
        "Pre-release" to PRE_SERVER_DOMAIN,
        "Test" to TEST_SERVER_DOMAIN
    )

    /** Display label for a domain ("Custom" if it isn't one of the known environments). */
    fun serverEnvironmentLabel(domain: String): String =
        serverEnvironments.firstOrNull { it.second == domain }?.first ?: "Custom"

    /** Runtime-selected server domain (Settings → Environment). Restart to take effect. */
    var serverDomainOverride: String?
        get() = prefs.getString("server_domain_override", null)
        set(value) = prefs.edit().putString("server_domain_override", value).apply()

    /** Default = prod environment (cloud bind/unbind testing); switch via Settings → Environment. */
    val activeServerDomain: String
        get() = serverDomainOverride ?: PROD_SERVER_DOMAIN

    /** Entered the app from Welcome without pairing a device ("Connect device later"). */
    var hasSkippedOnboarding: Boolean
        get() = prefs.getBoolean("has_skipped_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_skipped_onboarding", value).apply()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** "Never show again" preference for the WiFi fast-transfer confirmation sheet. */
    var fastTransferNeverShowAgain: Boolean
        get() = prefs.getBoolean(KEY_FAST_TRANSFER_HIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_TRANSFER_HIDE, value).apply()

    // --- Recording Files (JSON persistence) ---

    val allFiles: List<RecordingFile>
        get() = synchronized(lock) {
            loadFiles().sortedByDescending { it.createdAt }
        }

    fun addFiles(files: List<RecordingFile>) {
        synchronized(lock) {
            val existing = loadFiles().toMutableList()
            // Session IDs are only unique within one device family. A DingTalk A1 and a D3200
            // can both use the same Unix timestamp as their file ID, so include device identity.
            files.forEach { incoming ->
                val index = existing.indexOfFirst {
                    it.deviceSN == incoming.deviceSN && it.sessionId == incoming.sessionId
                }
                if (index < 0) {
                    existing += incoming
                } else {
                    val current = existing[index]
                    // A vendor catalog is registered before the bytes are downloaded. Upgrade that
                    // pending row in place so the Files screen keeps the same id, title and AI data.
                    existing[index] = incoming.copy(
                        id = current.id,
                        name = if (current.isTitleCustomized || !current.transcriptJSON.isNullOrBlank() ||
                            !current.summaryText.isNullOrBlank()
                        ) current.name else incoming.name,
                        duration = incoming.duration.takeIf { it > 0 } ?: current.duration,
                        syncedAt = incoming.syncedAt ?: current.syncedAt,
                        localPath = incoming.localPath ?: current.localPath,
                        summaryText = current.summaryText ?: incoming.summaryText,
                        transcriptJSON = current.transcriptJSON ?: incoming.transcriptJSON,
                        ideaShellNoteId = current.ideaShellNoteId ?: incoming.ideaShellNoteId,
                        ideaShellSyncedAt = current.ideaShellSyncedAt ?: incoming.ideaShellSyncedAt,
                        isTitleCustomized = current.isTitleCustomized
                    )
                }
            }
            saveFiles(existing)
        }
    }

    /**
     * Collapse rows created by the old A1 live-capture identity into the normal A1 identity.
     *
     * A completed live stream and the later device catalog entry describe the same recording
     * when their fid/sessionId matches. Older builds used different device SNs for those two
     * paths, which made [addFiles] correctly (but undesirably) keep both rows. This migration is
     * intentionally idempotent and only changes the JSON index: the captured Ogg file is kept.
     */
    fun mergeDingTalkLiveRows(canonicalDeviceSN: String, legacyLiveDeviceSN: String) {
        synchronized(lock) {
            val files = loadFiles()
            if (files.none { it.deviceSN == legacyLiveDeviceSN }) return

            val a1Rows = files.filter {
                it.deviceSN == canonicalDeviceSN || it.deviceSN == legacyLiveDeviceSN
            }
            val mergedRows = a1Rows.groupBy { it.sessionId }.values.map { rows ->
                val customized = rows.firstOrNull { it.isTitleCustomized }
                val processed = rows.firstOrNull {
                    !it.transcriptJSON.isNullOrBlank() || !it.summaryText.isNullOrBlank()
                }
                val local = rows.firstOrNull { row ->
                    row.localPath?.let { File(it).isFile } == true
                } ?: rows.firstOrNull { !it.localPath.isNullOrBlank() }
                val canonical = rows.firstOrNull { it.deviceSN == canonicalDeviceSN }
                val base = customized ?: processed ?: local ?: canonical ?: rows.first()

                base.copy(
                    id = "a1-${base.sessionId}",
                    deviceSN = canonicalDeviceSN,
                    name = (customized ?: processed ?: local ?: canonical ?: base).name,
                    duration = rows.maxOfOrNull { it.duration } ?: base.duration,
                    createdAt = rows.minOfOrNull { it.createdAt } ?: base.createdAt,
                    syncedAt = rows.mapNotNull { it.syncedAt }.maxOrNull(),
                    localPath = local?.localPath,
                    summaryText = rows.firstNotNullOfOrNull { it.summaryText?.takeIf(String::isNotBlank) },
                    transcriptJSON = rows.firstNotNullOfOrNull {
                        it.transcriptJSON?.takeIf(String::isNotBlank)
                    },
                    ideaShellNoteId = rows.firstNotNullOfOrNull {
                        it.ideaShellNoteId?.takeIf(String::isNotBlank)
                    },
                    ideaShellSyncedAt = rows.mapNotNull { it.ideaShellSyncedAt }.maxOrNull(),
                    isTitleCustomized = customized != null
                )
            }

            saveFiles(
                files.filterNot {
                    it.deviceSN == canonicalDeviceSN || it.deviceSN == legacyLiveDeviceSN
                } + mergedRows
            )
        }
    }

    /**
     * Replace one device's pending catalog while retaining every downloaded local copy.
     *
     * This is intentionally device-scoped: refreshing an A1 must not disturb PLAUD or D3200
     * entries, and a recording deleted from the A1 should disappear only while it is still a
     * remote-only row. A downloaded copy remains available in Files.
     */
    fun reconcileDeviceCatalog(deviceSN: String, remoteFiles: List<RecordingFile>) {
        synchronized(lock) {
            val existing = loadFiles().toMutableList()
            val remoteSessionIds = remoteFiles.mapTo(hashSetOf()) { it.sessionId }
            existing.removeAll {
                it.deviceSN == deviceSN && !it.isSynced && it.sessionId !in remoteSessionIds
            }

            remoteFiles.forEach { incoming ->
                val index = existing.indexOfFirst {
                    it.deviceSN == deviceSN && it.sessionId == incoming.sessionId
                }
                if (index < 0) {
                    existing += incoming
                } else {
                    val current = existing[index]
                    existing[index] = current.copy(
                        duration = incoming.duration.takeIf { it > 0 } ?: current.duration,
                        createdAt = current.createdAt,
                        // Keep the user's/generated title and all local processing results.
                        name = current.name,
                        localPath = current.localPath,
                        syncedAt = current.syncedAt,
                        summaryText = current.summaryText,
                        transcriptJSON = current.transcriptJSON,
                        isTitleCustomized = current.isTitleCustomized
                    )
                }
            }
            saveFiles(existing)
        }
    }

    fun deleteFile(file: RecordingFile) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.removeAll { it.id == file.id }
            saveFiles(files)
        }
        // Delete the local audio file
        file.localPath?.let { path ->
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    fun renameFile(file: RecordingFile, newName: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == file.id }?.apply {
                name = newName
                isTitleCustomized = true
            }
            saveFiles(files)
        }
    }

    /** Apply an AI title only to names created automatically by a recorder integration. */
    fun updateGeneratedTitle(id: String, generatedTitle: String) {
        val title = generatedTitle.trim().take(36)
        if (title.isBlank()) return
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            val file = files.find { it.id == id } ?: return@synchronized
            if (!file.isTitleCustomized && isAutomaticName(file.name)) {
                file.name = title
                saveFiles(files)
            }
        }
    }

    private fun isAutomaticName(name: String): Boolean {
        val value = name.trim()
        return value.isBlank() ||
            value.equals("Untitled Recording", ignoreCase = true) ||
            value.startsWith("A1 ") ||
            value.startsWith("录音豆 ") ||
            value.matches(Regex("(?i)^recording(?:[ _-].*)?$"))
    }

    fun replaceAllFiles(files: List<RecordingFile>) {
        synchronized(lock) {
            saveFiles(files)
        }
    }

    /** Backfill a real duration (seconds) for a file whose stored duration is 0. */
    fun updateDuration(id: String, durationSec: Long) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.duration = durationSec
            saveFiles(files)
        }
    }

    /** Persist a completed transcription (JSON array, same shape iOS stores) for a file id. */
    fun updateTranscript(id: String, transcriptJSON: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.transcriptJSON = transcriptJSON
            saveFiles(files)
        }
    }

    fun updateSummary(id: String, summary: String) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find { it.id == id }?.summaryText = summary
            saveFiles(files)
        }
    }

    /** Persist the remote note identity only after IdeaShell confirms a successful create/update. */
    fun updateIdeaShellReceipt(id: String, noteId: String, syncedAt: Long): Boolean =
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            val file = files.find { it.id == id } ?: return@synchronized false
            file.ideaShellNoteId = noteId
            file.ideaShellSyncedAt = syncedAt
            saveFiles(files)
            true
        }

    /** Persist one complete AI result atomically so the UI never observes a half-written result. */
    fun updateAiResult(id: String, transcriptJSON: String, summary: String, generatedTitle: String): Boolean =
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            val file = files.find { it.id == id } ?: return@synchronized false
            file.transcriptJSON = transcriptJSON
            file.summaryText = summary
            val title = generatedTitle.trim().take(36)
            if (title.isNotBlank() && !file.isTitleCustomized && isAutomaticName(file.name)) {
                file.name = title
            }
            saveFiles(files)
            true
        }

    fun markAsSynced(sessionId: Long, localPath: String, duration: Long, deviceSN: String? = null) {
        synchronized(lock) {
            val files = loadFiles().toMutableList()
            files.find {
                it.sessionId == sessionId && (deviceSN == null || it.deviceSN == deviceSN)
            }?.apply {
                this.localPath = localPath
                this.syncedAt = System.currentTimeMillis()
                this.duration = duration
            }
            saveFiles(files)
        }
    }

    fun audioFilePath(sessionId: Long): File {
        val dir = File(appContext.filesDir, "audio")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${sessionId}.opus")
    }

    val exportDir: File
        get() {
            val dir = File(appContext.cacheDir, "export")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun clearAll() {
        synchronized(lock) {
            saveFiles(emptyList())
            recordingsBackupFile().delete()
        }
        prefs.edit().clear().apply()
        // Delete the audio directory
        File(appContext.filesDir, "audio").takeIf { it.exists() }?.deleteRecursively()
    }

    // --- Private helpers ---

    private fun recordingsFile(): File = File(appContext.filesDir, RECORDINGS_FILE)

    private fun recordingsBackupFile(): File = File(appContext.filesDir, "$RECORDINGS_FILE.bak")

    private fun loadFiles(): List<RecordingFile> {
        val file = recordingsFile()
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<RecordingFile>>() {}.type
        fun parse(source: File): List<RecordingFile> =
            gson.fromJson<List<RecordingFile>>(source.readText(), type) ?: emptyList()

        return runCatching { parse(file) }.getOrElse { primaryError ->
            val backup = recordingsBackupFile()
            if (backup.exists()) {
                runCatching { parse(backup) }.getOrElse {
                    throw IllegalStateException("录音索引损坏，且备份无法恢复", primaryError)
                }
            } else {
                throw IllegalStateException("录音索引损坏", primaryError)
            }
        }
    }

    private fun saveFiles(files: List<RecordingFile>) {
        val target = recordingsFile()
        val temp = File(target.parentFile, "$RECORDINGS_FILE.tmp")
        val backup = recordingsBackupFile()
        val bytes = gson.toJson(files).toByteArray(Charsets.UTF_8)

        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (target.exists()) target.copyTo(backup, overwrite = true)
        android.system.Os.rename(temp.absolutePath, target.absolutePath)
    }
}
