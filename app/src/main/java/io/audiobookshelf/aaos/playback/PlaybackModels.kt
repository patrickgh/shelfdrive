package io.audiobookshelf.aaos.playback

import android.net.Uri

data class ResolvedAudiobookPlayback(
    val bookId: String,
    val title: String,
    val author: String?,
    val artworkUri: Uri?,
    val durationMs: Long?,
    val queue: List<PlaybackTrack>,
    val startIndex: Int,
    val startPositionMs: Long,
)

data class ResolvedAudiobookPlaybackSession(
    val playback: ResolvedAudiobookPlayback,
    val accessToken: String,
    val sessionId: String?,
)

data class PlaybackTrack(
    val id: String,
    val title: String,
    val contentUrl: String,
    val mimeType: String?,
    val durationMs: Long?,
    val startOffsetMs: Long,
)

data class QueueStartPosition(
    val trackIndex: Int,
    val positionMs: Long,
)

fun StoredPlaybackState.toResolvedPlayback(): ResolvedAudiobookPlayback? {
    if (queue.isEmpty()) {
        return null
    }
    val start = PlaybackQueueMath.locateStartPosition(queue, positionMs)
    return ResolvedAudiobookPlayback(
        bookId = bookId,
        title = title ?: "Hoerbuch",
        author = author,
        artworkUri = artworkUri,
        durationMs = durationMs,
        queue = queue,
        startIndex = start.trackIndex,
        startPositionMs = start.positionMs,
    )
}
