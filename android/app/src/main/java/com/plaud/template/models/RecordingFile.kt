package com.plaud.template.models

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class RecordingFile(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("sessionId")
    val sessionId: Long,

    @SerializedName("deviceSN")
    val deviceSN: String,

    @SerializedName("name")
    var name: String,

    @SerializedName("duration")
    var duration: Long,

    @SerializedName("createdAt")
    val createdAt: Long,

    @SerializedName("syncedAt")
    var syncedAt: Long? = null,

    @SerializedName("localPath")
    var localPath: String? = null,

    @SerializedName("summaryText")
    var summaryText: String? = null,

    @SerializedName("transcriptJSON")
    var transcriptJSON: String? = null,

    /** Remote receipt returned by IdeaShell. Reuse it to update instead of creating duplicates. */
    @SerializedName("ideaShellNoteId")
    var ideaShellNoteId: String? = null,

    @SerializedName("ideaShellSyncedAt")
    var ideaShellSyncedAt: Long? = null,

    /** True after an explicit user rename; generated titles must never overwrite it. */
    @SerializedName("isTitleCustomized")
    var isTitleCustomized: Boolean = false
) {
    val isSynced: Boolean
        get() = localPath != null
}
