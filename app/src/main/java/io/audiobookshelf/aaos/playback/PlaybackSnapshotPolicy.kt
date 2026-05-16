package io.audiobookshelf.aaos.playback

object PlaybackSnapshotPolicy {
    const val RESTORE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1_000L

    fun isRestorable(state: StoredPlaybackState, nowMs: Long): Boolean {
        return state.bookId.isNotBlank() &&
            state.updatedAt > 0L &&
            nowMs - state.updatedAt <= RESTORE_MAX_AGE_MS
    }
}
