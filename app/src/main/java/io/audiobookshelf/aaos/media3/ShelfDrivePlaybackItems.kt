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
internal fun ResolvedAudiobookPlayback.toMedia3PlaybackItems(): List<MediaItem> {
    return queue.map { track ->
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
}
