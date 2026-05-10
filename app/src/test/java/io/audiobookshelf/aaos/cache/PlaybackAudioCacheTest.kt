package io.audiobookshelf.aaos.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAudioCacheTest {
    @Test
    fun stableCacheKeyUsesBookAndTrackIdentity() {
        assertEquals(
            "shelfdrive-audio-v1:book-123:track-4",
            PlaybackAudioCache.stableCacheKey(" book-123 ", " track-4 "),
        )
    }
}
