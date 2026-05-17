package io.audiobookshelf.aaos.cache

import android.os.Bundle

data class CacheSnapshot(
    val totalBytes: Long = 0L,
    val catalogBytes: Long = 0L,
    val artworkBytes: Long = 0L,
    val fileCount: Int = 0,
    val clearedAt: Long? = null,
) {
    fun toBundle(): Bundle {
        return Bundle().apply {
            putLong(KEY_TOTAL_BYTES, totalBytes)
            putLong(KEY_CATALOG_BYTES, catalogBytes)
            putLong(KEY_ARTWORK_BYTES, artworkBytes)
            putInt(KEY_FILE_COUNT, fileCount)
            if (clearedAt != null) {
                putLong(KEY_CLEARED_AT, clearedAt)
            }
        }
    }

    companion object {
        private const val KEY_TOTAL_BYTES = "cache_total_bytes"
        private const val KEY_CATALOG_BYTES = "cache_catalog_bytes"
        private const val KEY_ARTWORK_BYTES = "cache_artwork_bytes"
        private const val KEY_FILE_COUNT = "cache_file_count"
        private const val KEY_CLEARED_AT = "cache_cleared_at"

        fun fromBundle(bundle: Bundle?): CacheSnapshot? {
            bundle ?: return null
            return CacheSnapshot(
                totalBytes = bundle.getLong(KEY_TOTAL_BYTES),
                catalogBytes = bundle.getLong(KEY_CATALOG_BYTES),
                artworkBytes = bundle.getLong(KEY_ARTWORK_BYTES),
                fileCount = bundle.getInt(KEY_FILE_COUNT),
                clearedAt = if (bundle.containsKey(KEY_CLEARED_AT)) bundle.getLong(KEY_CLEARED_AT) else null,
            )
        }
    }
}
