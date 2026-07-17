package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCachePolicyTest {
    @Test
    fun `short tracks fill twenty minute horizon`() {
        val queue = List(12) { index -> track(index, durationMs = 2L * 60L * 1_000L) }

        assertEquals(
            (1..10).toList(),
            PlaybackCachePolicy.followingTrackIndices(queue, currentTrackIndex = 0),
        )
    }

    @Test
    fun `one long following track satisfies horizon`() {
        val queue = listOf(
            track(0, durationMs = 5L * 60L * 1_000L),
            track(1, durationMs = 30L * 60L * 1_000L),
            track(2, durationMs = 30L * 60L * 1_000L),
        )

        assertEquals(
            listOf(1),
            PlaybackCachePolicy.followingTrackIndices(queue, currentTrackIndex = 0),
        )
    }

    @Test
    fun `unknown duration is treated as sufficient horizon`() {
        val queue = listOf(track(0, durationMs = 60_000L), track(1, durationMs = null), track(2, durationMs = 60_000L))

        assertEquals(
            listOf(1),
            PlaybackCachePolicy.followingTrackIndices(queue, currentTrackIndex = 0),
        )
    }

    private fun track(index: Int, durationMs: Long?): PlaybackTrack {
        return PlaybackTrack(
            id = "track-$index",
            title = "Track $index",
            contentUrl = "https://example.com/$index.mp3",
            mimeType = "audio/mpeg",
            durationMs = durationMs,
            startOffsetMs = 0L,
        )
    }
}
