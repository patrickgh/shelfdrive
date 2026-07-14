package io.audiobookshelf.aaos.progress

data class PlaybackProgressSnapshot(
    val bookId: String,
    val playbackSessionId: String?,
    val currentTimeMs: Long,
    val durationMs: Long?,
    val timeListenedMs: Long,
    val isFinished: Boolean,
    val reason: PlaybackProgressReason,
    val lastUpdateAt: Long = System.currentTimeMillis(),
    val offlineStarted: Boolean = false,
)

enum class PlaybackProgressReason(val shouldRefreshBrowse: Boolean) {
    PERIODIC(false),
    SEEKED(false),
    PAUSED(true),
    STOPPED(true),
    TRACK_CHANGED(true),
    ENDED(true),
}
