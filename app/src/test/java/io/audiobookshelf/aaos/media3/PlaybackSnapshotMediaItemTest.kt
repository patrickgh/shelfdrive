package io.audiobookshelf.aaos.media3

import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackSnapshotMediaItemTest {
    @Test
    fun `metadata resumption item does not contain fake playable audio`() {
        val item = StoredPlaybackState(
            bookId = "book-1",
            title = "Book",
            author = "Author",
            durationMs = 120_000L,
            positionMs = 42_000L,
            playbackSpeed = 1.25f,
        )
            .toMedia3MetadataItem()

        assertEquals("book:book-1", item.mediaId)
        assertEquals("Author", item.mediaMetadata.author)
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, item.mediaMetadata.mediaType)
        assertNull(item.localConfiguration)
    }
}
