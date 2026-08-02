package io.audiobookshelf.aaos.media3

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.cache.PlaybackAudioCache
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback
import io.audiobookshelf.aaos.playback.StoredPlaybackState

@OptIn(UnstableApi::class)
internal fun ResolvedAudiobookPlayback.toMedia3PlaybackItems(): List<MediaItem> {
    return queue.map { track ->
        MediaItem.Builder()
            .setMediaId(BrowseNodeId.Book(bookId).serialize())
            .setUri(track.contentUrl)
            .setMimeType(track.mimeType)
            .setCustomCacheKey(PlaybackAudioCache.stableCacheKey(bookId, track.id))
            .setMediaMetadata(playbackMetadata(title, author, title, artworkUri, durationMs ?: track.durationMs))
            .build()
    }
}

internal fun StoredPlaybackState.toMedia3MetadataItem(): MediaItem {
    return MediaItem.Builder()
        .setMediaId(BrowseNodeId.Book(bookId).serialize())
        .setMediaMetadata(playbackMetadata(title ?: "Hoerbuch", author, title, artworkUri, durationMs))
        .build()
}

@OptIn(UnstableApi::class)
private fun playbackMetadata(
    title: String,
    author: String?,
    albumTitle: String?,
    artworkUri: Uri?,
    durationMs: Long?,
): MediaMetadata {
    return MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(author)
        .setAlbumTitle(albumTitle)
        .setAlbumArtist(author)
        .setArtworkUri(artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setDurationMs(durationMs)
        .build()
}
