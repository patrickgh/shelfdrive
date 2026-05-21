package io.audiobookshelf.aaos.mediacompat

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.browser.BrowseNodeId
import io.audiobookshelf.aaos.playback.ResolvedAudiobookPlayback

@OptIn(UnstableApi::class)
internal object ShelfDrivePlaybackItems {
    fun build(playback: ResolvedAudiobookPlayback): List<MediaItem> {
        return playback.queue.map { track ->
            MediaItem.Builder()
                .setMediaId("${BrowseNodeId.Book(playback.bookId).serialize()}:${track.id}")
                .setUri(track.contentUrl)
                .setMimeType(track.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(playback.title)
                        .setArtist(playback.author)
                        .setAlbumTitle(playback.title)
                        .setAlbumArtist(playback.author)
                        .setArtworkUri(playback.artworkUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setDurationMs(playback.durationMs ?: track.durationMs)
                        .build(),
                )
                .build()
        }
    }
}
