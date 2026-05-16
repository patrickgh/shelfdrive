package io.audiobookshelf.aaos.media3

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.cache.PlaybackAudioCache
import io.audiobookshelf.aaos.playback.QueueStartPosition
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback

@OptIn(UnstableApi::class)
internal fun ResolvedAudiobookPlayback.toMedia3PlaybackWindow(
    startPosition: QueueStartPosition,
    windowSize: Int,
): PlaybackMediaWindow {
    val windowRange = playbackWindowRange(
        queueSize = queue.size,
        startTrackIndex = startPosition.trackIndex,
        windowSize = windowSize,
    )
    if (windowRange.isEmpty()) {
        return PlaybackMediaWindow(
            items = emptyList(),
            startIndex = 0,
            startPositionMs = 0L,
            globalStartIndex = 0,
        )
    }
    val windowStart = windowRange.first
    val items = queue
        .slice(windowRange)
        .map { track ->
            MediaItem.Builder()
                .setMediaId("${BrowseNodeId.Book(bookId).serialize()}:${track.id}")
                .setUri(track.contentUrl)
                .setMimeType(track.mimeType)
                .setCustomCacheKey(PlaybackAudioCache.stableCacheKey(bookId, track.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(author)
                        .setAlbumTitle(title)
                        .setAlbumArtist(author)
                        .setArtworkUri(artworkUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setDurationMs(durationMs ?: track.durationMs)
                        .build(),
                )
                .build()
        }
    return PlaybackMediaWindow(
        items = items,
        startIndex = 0,
        startPositionMs = startPosition.positionMs,
        globalStartIndex = windowStart,
    )
}

internal fun playbackWindowRange(
    queueSize: Int,
    startTrackIndex: Int,
    windowSize: Int,
): IntRange {
    if (queueSize <= 0) {
        return IntRange.EMPTY
    }
    val windowStart = startTrackIndex.coerceIn(0, queueSize - 1)
    val windowEndExclusive = (windowStart + windowSize.coerceAtLeast(1)).coerceAtMost(queueSize)
    return windowStart until windowEndExclusive
}

internal data class PlaybackMediaWindow(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val globalStartIndex: Int,
)
