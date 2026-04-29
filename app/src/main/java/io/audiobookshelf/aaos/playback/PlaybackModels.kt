package io.audiobookshelf.aaos.playback

import android.net.Uri

data class ResolvedAudiobookPlayback(
    val bookId: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val artworkUri: Uri?,
    val durationMs: Long?,
    val queue: List<ResolvedPlaybackTrack>,
    val startIndex: Int,
    val startPositionMs: Long,
)

data class ResolvedAudiobookPlaybackSession(
    val playback: ResolvedAudiobookPlayback,
    val accessToken: String,
)

data class ResolvedPlaybackTrack(
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
