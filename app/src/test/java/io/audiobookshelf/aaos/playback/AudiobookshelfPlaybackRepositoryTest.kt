package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookshelfPlaybackRepositoryTest {
    @Test
    fun `direct play uses a logical playback track uri`() {
        val url = playbackTrackUri("book-123", 17)

        assertEquals(
            "shelfdrive://book/book-123/track/17",
            url,
        )
    }

    @Test
    fun `logical playback track uri parses book and track`() {
        assertEquals(
            PlaybackTrackUri("book-123", 17),
            parsePlaybackTrackUri("shelfdrive://book/book-123/track/17"),
        )
    }

    @Test
    fun `session route is built only when data source resolves the logical uri`() {
        assertEquals(
            "https://abs.example.com/base/public/session/session-456/track/17",
            playbackSessionTrackUrl(
                baseUrl = "https://abs.example.com/base/",
                sessionId = "session-456",
                trackIndex = 17,
            ),
        )
    }

    @Test
    fun `only logical playback uris are recognized`() {
        assertTrue(isShelfDrivePlaybackUri("shelfdrive://book/book-123/track/1"))
        assertFalse(isShelfDrivePlaybackUri("https://abs.example.com/public/session/old/track/1"))
        assertFalse(isShelfDrivePlaybackUri("https://abs.example.com/audio/book.m4b"))
    }
}
