package io.audiobookshelf.aaos.playback

internal object PlaybackCachePolicy {
    const val FORWARD_CACHE_TARGET_MS = 20L * 60L * 1_000L

    fun followingTrackIndices(
        queue: List<PlaybackTrack>,
        currentTrackIndex: Int,
    ): List<Int> {
        if (currentTrackIndex !in queue.indices) {
            return emptyList()
        }
        var durationMs = 0L
        return buildList {
            for (trackIndex in currentTrackIndex + 1 until queue.size) {
                add(trackIndex)
                durationMs += queue[trackIndex].durationMs
                    ?.coerceAtLeast(0L)
                    ?: FORWARD_CACHE_TARGET_MS
                if (durationMs >= FORWARD_CACHE_TARGET_MS) {
                    break
                }
            }
        }
    }
}
