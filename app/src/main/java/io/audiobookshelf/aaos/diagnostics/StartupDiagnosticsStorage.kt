package io.audiobookshelf.aaos.diagnostics

import android.content.Context
import androidx.core.content.edit

class StartupDiagnosticsStorage(context: Context) {
    private val sharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): StartupDiagnosticsSnapshot {
        return StartupDiagnosticsSnapshot(
            lastServiceStartedAt = sharedPreferences.getLongOrNull(KEY_LAST_SERVICE_STARTED_AT),
            lastRestoreStartedAt = sharedPreferences.getLongOrNull(KEY_LAST_RESTORE_STARTED_AT),
            lastRestoreFinishedAt = sharedPreferences.getLongOrNull(KEY_LAST_RESTORE_FINISHED_AT),
            lastRestoreStatus = sharedPreferences.getString(KEY_LAST_RESTORE_STATUS, null)
                ?.let { runCatching { PlaybackRestoreStatus.valueOf(it) }.getOrNull() },
            lastRestoreBookId = sharedPreferences.getString(KEY_LAST_RESTORE_BOOK_ID, null)
                ?.takeIf { it.isNotBlank() },
            lastRestoreMessage = sharedPreferences.getString(KEY_LAST_RESTORE_MESSAGE, null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    fun recordServiceStarted(timestamp: Long = System.currentTimeMillis()) {
        sharedPreferences.edit {
            putLong(KEY_LAST_SERVICE_STARTED_AT, timestamp)
        }
    }

    fun recordRestoreStarted(bookId: String?, timestamp: Long = System.currentTimeMillis()) {
        sharedPreferences.edit {
            putLong(KEY_LAST_RESTORE_STARTED_AT, timestamp)
            remove(KEY_LAST_RESTORE_FINISHED_AT)
            putString(KEY_LAST_RESTORE_STATUS, PlaybackRestoreStatus.RUNNING.name)
            putString(KEY_LAST_RESTORE_BOOK_ID, bookId)
            remove(KEY_LAST_RESTORE_MESSAGE)
        }
    }

    fun recordRestoreFinished(
        status: PlaybackRestoreStatus,
        message: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        sharedPreferences.edit {
            putLong(KEY_LAST_RESTORE_FINISHED_AT, timestamp)
            putString(KEY_LAST_RESTORE_STATUS, status.name)
            putString(KEY_LAST_RESTORE_MESSAGE, message?.take(MAX_MESSAGE_LENGTH))
        }
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? {
        return if (contains(key)) getLong(key, 0L) else null
    }

    companion object {
        private const val FILE_NAME = "startup_diagnostics"
        private const val KEY_LAST_SERVICE_STARTED_AT = "last_service_started_at"
        private const val KEY_LAST_RESTORE_STARTED_AT = "last_restore_started_at"
        private const val KEY_LAST_RESTORE_FINISHED_AT = "last_restore_finished_at"
        private const val KEY_LAST_RESTORE_STATUS = "last_restore_status"
        private const val KEY_LAST_RESTORE_BOOK_ID = "last_restore_book_id"
        private const val KEY_LAST_RESTORE_MESSAGE = "last_restore_message"
        private const val MAX_MESSAGE_LENGTH = 240
    }
}

data class StartupDiagnosticsSnapshot(
    val lastServiceStartedAt: Long? = null,
    val lastRestoreStartedAt: Long? = null,
    val lastRestoreFinishedAt: Long? = null,
    val lastRestoreStatus: PlaybackRestoreStatus? = null,
    val lastRestoreBookId: String? = null,
    val lastRestoreMessage: String? = null,
)

enum class PlaybackRestoreStatus {
    RUNNING,
    SKIPPED,
    SUCCESS,
    FAILED,
    TIMED_OUT,
}
