package io.audiobookshelf.aaos.playback

object PlaybackQueueMath {
    fun locateStartPosition(
        queue: List<ResolvedPlaybackTrack>,
        requestedPositionMs: Long,
    ): QueueStartPosition {
        if (queue.isEmpty()) {
            return QueueStartPosition(trackIndex = 0, positionMs = 0L)
        }

        val normalizedRequest = requestedPositionMs.coerceAtLeast(0L)
        queue.forEachIndexed { index, track ->
            val trackStart = track.startOffsetMs.coerceAtLeast(0L)
            val trackDuration = track.durationMs ?: Long.MAX_VALUE
            val trackEndExclusive = if (trackDuration == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                trackStart + trackDuration
            }
            if (normalizedRequest in trackStart until trackEndExclusive) {
                return QueueStartPosition(
                    trackIndex = index,
                    positionMs = (normalizedRequest - trackStart).coerceAtLeast(0L),
                )
            }
        }

        val lastIndex = queue.lastIndex
        val lastTrack = queue[lastIndex]
        val lastTrackStart = lastTrack.startOffsetMs.coerceAtLeast(0L)
        val offset = (normalizedRequest - lastTrackStart).coerceAtLeast(0L)
        val boundedOffset = lastTrack.durationMs?.let { offset.coerceAtMost(it) } ?: offset
        return QueueStartPosition(trackIndex = lastIndex, positionMs = boundedOffset)
    }
}
