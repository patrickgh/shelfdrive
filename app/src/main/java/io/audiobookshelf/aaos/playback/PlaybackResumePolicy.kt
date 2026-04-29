package io.audiobookshelf.aaos.playback

import io.audiobookshelf.aaos.progress.PlaybackProgressReason

object PlaybackResumePolicy {
    const val REWIND_ON_PAUSE_MS = 15_000L

    fun positionAfterPause(currentPositionMs: Long): Long {
        return (currentPositionMs.coerceAtLeast(0L) - REWIND_ON_PAUSE_MS).coerceAtLeast(0L)
    }
}
