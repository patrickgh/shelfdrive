package io.audiobookshelf.aaos.diagnostics

import android.content.Context

class DiagnosticsUploadStorage(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): DiagnosticsUploadSnapshot {
        return DiagnosticsUploadSnapshot(
            uploadUrl = sharedPreferences.getString(KEY_UPLOAD_URL, null)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_UPLOAD_URL,
            lastUploadStartedAt = sharedPreferences.getLongOrNull(KEY_LAST_UPLOAD_STARTED_AT),
            lastUploadFinishedAt = sharedPreferences.getLongOrNull(KEY_LAST_UPLOAD_FINISHED_AT),
            lastUploadStatus = sharedPreferences.getString(KEY_LAST_UPLOAD_STATUS, null)
                ?.let { runCatching { DiagnosticsUploadStatus.valueOf(it) }.getOrNull() },
            lastUploadMessage = sharedPreferences.getString(KEY_LAST_UPLOAD_MESSAGE, null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    fun saveUploadUrl(uploadUrl: String) {
        sharedPreferences.edit()
            .putString(KEY_UPLOAD_URL, uploadUrl.trim())
            .apply()
    }

    fun recordUploadStarted(timestamp: Long = System.currentTimeMillis()) {
        sharedPreferences.edit()
            .putLong(KEY_LAST_UPLOAD_STARTED_AT, timestamp)
            .remove(KEY_LAST_UPLOAD_FINISHED_AT)
            .putString(KEY_LAST_UPLOAD_STATUS, DiagnosticsUploadStatus.RUNNING.name)
            .remove(KEY_LAST_UPLOAD_MESSAGE)
            .apply()
    }

    fun recordUploadFinished(
        status: DiagnosticsUploadStatus,
        message: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        sharedPreferences.edit()
            .putLong(KEY_LAST_UPLOAD_FINISHED_AT, timestamp)
            .putString(KEY_LAST_UPLOAD_STATUS, status.name)
            .putString(KEY_LAST_UPLOAD_MESSAGE, message.take(MAX_MESSAGE_LENGTH))
            .apply()
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? {
        return if (contains(key)) getLong(key, 0L) else null
    }

    companion object {
        const val DEFAULT_UPLOAD_URL = "https://shelfdev.mooo.com:23377/"

        private const val FILE_NAME = "diagnostics_upload"
        private const val KEY_UPLOAD_URL = "upload_url"
        private const val KEY_LAST_UPLOAD_STARTED_AT = "last_upload_started_at"
        private const val KEY_LAST_UPLOAD_FINISHED_AT = "last_upload_finished_at"
        private const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
        private const val KEY_LAST_UPLOAD_MESSAGE = "last_upload_message"
        private const val MAX_MESSAGE_LENGTH = 240
    }
}

data class DiagnosticsUploadSnapshot(
    val uploadUrl: String = "",
    val lastUploadStartedAt: Long? = null,
    val lastUploadFinishedAt: Long? = null,
    val lastUploadStatus: DiagnosticsUploadStatus? = null,
    val lastUploadMessage: String? = null,
)

enum class DiagnosticsUploadStatus {
    RUNNING,
    SUCCESS,
    FAILED,
}
