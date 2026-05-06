package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotPolicyTest {
    @Test
    fun `accepts recent stored playback state`() {
        val state = storedState(updatedAt = 100_000L)

        assertTrue(PlaybackSnapshotPolicy.isRestorable(state, nowMs = 110_000L))
    }

    @Test
    fun `rejects stale stored playback state`() {
        val state = storedState(updatedAt = 1_000L)

        assertFalse(
            PlaybackSnapshotPolicy.isRestorable(
                state,
                nowMs = 1_000L + PlaybackSnapshotPolicy.RESTORE_MAX_AGE_MS + 1L,
            ),
        )
    }

    @Test
    fun `builds placeholder uri from stored book id`() {
        val state = storedState(
            title = "The Stored Book",
            author = "A. Narrator",
            durationMs = 3_600_000L,
        )

        assertEquals(
            "data:audio/wav;base64,UklGRjQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YRAAAAAAAAAAAAAAAAAAAAAA",
            PlaybackSnapshotPolicy.placeholderUri(state),
        )
    }

    private fun storedState(
        title: String? = "Book",
        author: String? = "Author",
        durationMs: Long? = 120_000L,
        updatedAt: Long = 10_000L,
    ): StoredPlaybackState {
        return StoredPlaybackState(
            bookId = "book-1",
            title = title,
            author = author,
            durationMs = durationMs,
            positionMs = 42_000L,
            trackIndex = 1,
            playbackSpeed = 1.25f,
            wasPlaying = false,
            updatedAt = updatedAt,
        )
    }
}
