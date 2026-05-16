package io.audiobookshelf.aaos.media3

import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfDrivePlaybackItemsTest {
    @Test
    fun `playback window exposes only current and following tracks`() {
        val range = playbackWindowRange(queueSize = 213, startTrackIndex = 5, windowSize = 5)

        assertEquals(5, range.first)
        assertEquals(9, range.last)
        assertEquals(5, range.count())
    }

    @Test
    fun `playback window clamps near the end of the queue`() {
        val range = playbackWindowRange(queueSize = 7, startTrackIndex = 6, windowSize = 5)

        assertEquals(6, range.first)
        assertEquals(6, range.last)
        assertEquals(1, range.count())
    }

    @Test
    fun `playback window clamps invalid start to available queue`() {
        val range = playbackWindowRange(queueSize = 3, startTrackIndex = 99, windowSize = 5)

        assertEquals(2, range.first)
        assertEquals(2, range.last)
        assertEquals(1, range.count())
    }
}
