package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackModelsTest {
    @Test
    fun `builds offline playback directly from stored queue regardless of snapshot age`() {
        val state = storedState().copy(
            queue = listOf(
                PlaybackTrack("one", "One", "https://example.com/one.mp3", "audio/mpeg", 60_000L, 0L),
                PlaybackTrack("two", "Two", "https://example.com/two.mp3", "audio/mpeg", 60_000L, 60_000L),
            ),
            positionMs = 75_000L,
        )

        val playback = state.toResolvedPlayback()

        assertTrue(playback != null)
        assertTrue(playback?.startIndex == 1)
        assertTrue(playback?.startPositionMs == 15_000L)
    }

    private fun storedState(
        title: String? = "Book",
        author: String? = "Author",
        durationMs: Long? = 120_000L,
    ): StoredPlaybackState {
        return StoredPlaybackState(
            bookId = "book-1",
            title = title,
            author = author,
            durationMs = durationMs,
            positionMs = 42_000L,
            playbackSpeed = 1.25f,
        )
    }
}
