package io.audiobookshelf.aaos.media3

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback

@OptIn(UnstableApi::class)
internal fun ResolvedAudiobookPlayback.toMedia3PlayableItems(): List<MediaItem> {
    val browserMediaId = BrowseNodeId.Book(bookId).serialize()
    return queue.map { track ->
        MediaItem.Builder()
            .setMediaId("${browserMediaId}:${track.id}")
            .setUri(track.contentUrl)
            .setMimeType(track.mimeType)
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
