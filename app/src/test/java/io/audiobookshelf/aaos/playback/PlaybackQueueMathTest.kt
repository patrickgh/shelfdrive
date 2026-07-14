package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueMathTest {
    @Test
    fun `maps resume position into matching track`() {
        val queue = listOf(
            PlaybackTrack("1", "Teil 1", "https://example.com/1.mp3", "audio/mpeg", 60_000L, 0L),
            PlaybackTrack("2", "Teil 2", "https://example.com/2.mp3", "audio/mpeg", 40_000L, 60_000L),
            PlaybackTrack("3", "Teil 3", "https://example.com/3.mp3", "audio/mpeg", 30_000L, 100_000L),
        )

        val result = PlaybackQueueMath.locateStartPosition(queue, 72_500L)

        assertEquals(1, result.trackIndex)
        assertEquals(12_500L, result.positionMs)
    }

    @Test
    fun `clamps resume position to last track when beyond total duration`() {
        val queue = listOf(
            PlaybackTrack("1", "Teil 1", "https://example.com/1.mp3", "audio/mpeg", 60_000L, 0L),
            PlaybackTrack("2", "Teil 2", "https://example.com/2.mp3", "audio/mpeg", 40_000L, 60_000L),
        )

        val result = PlaybackQueueMath.locateStartPosition(queue, 120_000L)

        assertEquals(1, result.trackIndex)
        assertEquals(40_000L, result.positionMs)
    }

    @Test
    fun `clamps negative resume position to first track`() {
        val queue = listOf(
            PlaybackTrack("1", "Teil 1", "https://example.com/1.mp3", "audio/mpeg", 60_000L, 0L),
            PlaybackTrack("2", "Teil 2", "https://example.com/2.mp3", "audio/mpeg", 40_000L, 60_000L),
        )

        val result = PlaybackQueueMath.locateStartPosition(queue, -5_000L)

        assertEquals(0, result.trackIndex)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun `maps exact track boundary to next track`() {
        val queue = listOf(
            PlaybackTrack("1", "Teil 1", "https://example.com/1.mp3", "audio/mpeg", 60_000L, 0L),
            PlaybackTrack("2", "Teil 2", "https://example.com/2.mp3", "audio/mpeg", 40_000L, 60_000L),
        )

        val result = PlaybackQueueMath.locateStartPosition(queue, 60_000L)

        assertEquals(1, result.trackIndex)
        assertEquals(0L, result.positionMs)
    }
}
