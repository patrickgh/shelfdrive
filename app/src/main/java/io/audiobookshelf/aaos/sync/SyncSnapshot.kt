package io.audiobookshelf.aaos.sync

import android.os.Bundle

data class SyncSnapshot(
    val status: SyncStatus,
    val libraryCount: Int = 0,
    val bookCount: Int = 0,
    val authorCount: Int = 0,
    val lastSyncedAt: Long? = null,
    val serverVersion: String? = null,
    val message: String? = null,
) {
    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(KEY_STATUS, status.name)
            putInt(KEY_LIBRARY_COUNT, libraryCount)
            putInt(KEY_BOOK_COUNT, bookCount)
            putInt(KEY_AUTHOR_COUNT, authorCount)
            if (lastSyncedAt != null) {
                putLong(KEY_LAST_SYNCED_AT, lastSyncedAt)
            }
            putString(KEY_SERVER_VERSION, serverVersion)
            putString(KEY_MESSAGE, message)
        }
    }

    companion object {
        private const val KEY_STATUS = "sync_status"
        private const val KEY_LIBRARY_COUNT = "sync_library_count"
        private const val KEY_BOOK_COUNT = "sync_book_count"
        private const val KEY_AUTHOR_COUNT = "sync_author_count"
        private const val KEY_LAST_SYNCED_AT = "sync_last_synced_at"
        private const val KEY_SERVER_VERSION = "sync_server_version"
        private const val KEY_MESSAGE = "sync_message"

        fun fromBundle(bundle: Bundle?): SyncSnapshot? {
            bundle ?: return null
            val status = runCatching { SyncStatus.valueOf(bundle.getString(KEY_STATUS).orEmpty()) }.getOrNull()
                ?: return null
            val hasSyncTime = bundle.containsKey(KEY_LAST_SYNCED_AT)
            return SyncSnapshot(
                status = status,
                libraryCount = bundle.getInt(KEY_LIBRARY_COUNT),
                bookCount = bundle.getInt(KEY_BOOK_COUNT),
                authorCount = bundle.getInt(KEY_AUTHOR_COUNT),
                lastSyncedAt = if (hasSyncTime) bundle.getLong(KEY_LAST_SYNCED_AT) else null,
                serverVersion = bundle.getString(KEY_SERVER_VERSION),
                message = bundle.getString(KEY_MESSAGE),
            )
        }
    }
}
