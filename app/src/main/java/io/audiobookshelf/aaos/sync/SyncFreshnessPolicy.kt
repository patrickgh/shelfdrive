package io.audiobookshelf.aaos.sync

object SyncFreshnessPolicy {
    const val DEFAULT_MAX_CACHE_AGE_MS: Long = 6L * 60L * 60L * 1000L

    fun shouldRefresh(
        snapshot: SyncSnapshot,
        nowMs: Long = System.currentTimeMillis(),
        maxCacheAgeMs: Long = DEFAULT_MAX_CACHE_AGE_MS,
    ): Boolean {
        val lastSyncedAt = snapshot.lastSyncedAt ?: return true
        if (snapshot.status == SyncStatus.IDLE) {
            return true
        }
        if (snapshot.libraryCount == 0 && snapshot.bookCount == 0) {
            return true
        }
        return nowMs - lastSyncedAt >= maxCacheAgeMs
    }
}
