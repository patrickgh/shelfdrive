package io.audiobookshelf.aaos.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.browser.BrowseNodeId

object PlaybackSnapshotPolicy {
    const val RESTORE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1_000L

    fun isRestorable(state: StoredPlaybackState, nowMs: Long): Boolean {
        return state.bookId.isNotBlank() &&
            state.updatedAt > 0L &&
            nowMs - state.updatedAt <= RESTORE_MAX_AGE_MS
    }

    fun placeholderUri(): String {
        return SILENT_WAV_DATA_URI
    }

    @OptIn(UnstableApi::class)
    fun placeholderMediaItem(state: StoredPlaybackState): MediaItem {
        return MediaItem.Builder()
            .setMediaId(BrowseNodeId.Book(state.bookId).serialize())
            .setUri(placeholderUri())
            .setMimeType("audio/wav")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title ?: "Hoerbuch")
                    .setArtist(state.author)
                    .setAlbumTitle(state.title)
                    .setAlbumArtist(state.author)
                    .setArtworkUri(state.artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setDurationMs(state.durationMs)
                    .build(),
            )
            .build()
    }

    @OptIn(UnstableApi::class)
    fun storedStateFromBrowseItem(
        bookId: String,
        item: MediaItem,
        nowMs: Long,
    ): StoredPlaybackState {
        return StoredPlaybackState(
            bookId = bookId,
            title = item.mediaMetadata.title?.toString(),
            author = item.mediaMetadata.artist?.toString(),
            artworkUri = item.mediaMetadata.artworkUri,
            durationMs = item.mediaMetadata.durationMs,
            positionMs = 0L,
            playbackSpeed = 1f,
            updatedAt = nowMs,
        )
    }

    private const val SILENT_WAV_DATA_URI =
        "data:audio/wav;base64,UklGRjQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YRAAAAAAAAAAAAAAAAAAAAAA"
}
