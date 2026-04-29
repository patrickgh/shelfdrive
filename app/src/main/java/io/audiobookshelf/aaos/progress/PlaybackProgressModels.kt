package io.audiobookshelf.aaos.progress

data class PlaybackProgressSnapshot(
    val bookId: String,
    val currentTimeMs: Long,
    val durationMs: Long?,
    val isFinished: Boolean,
    val reason: PlaybackProgressReason,
)

enum class PlaybackProgressReason(val shouldRefreshBrowse: Boolean) {
    PERIODIC(false),
    SEEKED(false),
    PAUSED(true),
    STOPPED(true),
    TRACK_CHANGED(true),
    ENDED(true),
}
