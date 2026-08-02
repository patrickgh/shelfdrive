package io.audiobookshelf.aaos.media3

import io.audiobookshelf.aaos.playback.StoredPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertNull(item.localConfiguration)
    }
}
