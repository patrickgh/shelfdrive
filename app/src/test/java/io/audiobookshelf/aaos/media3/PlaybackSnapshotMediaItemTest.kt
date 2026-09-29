package io.audiobookshelf.aaos.media3

import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.audiobookshelf.aaos.playback.StoredPlaybackState
import io.audiobookshelf.aaos.playback.PlaybackChapter
import io.audiobookshelf.aaos.playback.playbackProgressRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackSnapshotMediaItemTest {
    @Test
    fun `chapter preview reports chapter duration while retaining the book identity`() {
        val stored = StoredPlaybackState(
            bookId = "book-1",
            title = "Book",
            author = "Author",
            durationMs = 180_000L,
            positionMs = 75_000L,
            playbackSpeed = 1f,
            chapters = listOf(PlaybackChapter("Chapter two", 60_000L, 180_000L)),
        )
        val range = playbackProgressRange(stored.chapters, stored.durationMs, stored.positionMs, true)
        val item = stored.toMedia3MetadataItem(range)
        assertEquals("book:book-1", item.mediaId)
        assertEquals(120_000L, item.mediaMetadata.durationMs)
        assertEquals("Chapter two", item.mediaMetadata.subtitle)
        assertEquals("Author", item.mediaMetadata.artist)
        assertEquals(15_000L, range.relativePosition(stored.positionMs))
        assertNull(item.localConfiguration)

        val fallback = stored.copy(chapters = emptyList()).toMedia3MetadataItem(
            chaptersUnavailableText = "Chapter information unavailable",
        )
        assertEquals(180_000L, fallback.mediaMetadata.durationMs)
        assertEquals("Chapter information unavailable", fallback.mediaMetadata.artist)
        assertEquals("Author", fallback.mediaMetadata.author)
    }

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
