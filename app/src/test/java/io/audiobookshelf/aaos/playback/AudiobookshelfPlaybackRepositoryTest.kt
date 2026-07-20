package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookshelfPlaybackRepositoryTest {
    @Test
    fun `direct play uses public playback session track route`() {
        val url = playbackSessionTrackUrl(
            baseUrl = "https://abs.example.com/base/",
            sessionId = "session-123",
            trackIndex = 17,
        )

        assertEquals(
            "https://abs.example.com/base/public/session/session-123/track/17",
            url,
        )
    }
}
