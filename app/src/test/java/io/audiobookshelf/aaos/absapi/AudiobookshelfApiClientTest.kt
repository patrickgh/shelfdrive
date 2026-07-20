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
                lastUpdateAt = 1_713_456_789_000L,
            ),
        )

        assertEquals(12.345, fields["currentTime"])
        assertEquals(100.0, fields["duration"])
        assertEquals(5.432, fields["timeListened"])
        assertEquals(1_713_456_789_000L, fields["lastUpdate"])
    }

    @Test
    fun `authorization uses current ABS post endpoint`() {
        val request = authorizationRequest("access-token")

        assertEquals("/api/authorize", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
    }
}
