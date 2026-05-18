package io.audiobookshelf.aaos.absapi

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookshelfApiClientTest {

    @Test
    fun `playback session update uses seconds for ABS positions`() {
        val fields = playbackSessionUpdateRequestFields(
            PlaybackSessionUpdateRequest(
                currentTimeMs = 12_345L,
                durationMs = 100_000L,
                timeListenedMs = 5_432L,
            ),
        )

        assertEquals(12.345, fields["currentTime"])
        assertEquals(100.0, fields["duration"])
        assertEquals(5.432, fields["timeListened"])
    }
}
