package io.audiobookshelf.aaos.progress

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressConflictPolicyTest {

    @Test
    fun `applies newer server progress when playback is not active`() {
        val resolution = ProgressConflictPolicy.resolve(
            local = progress(currentTimeMs = 10_000L, lastUpdateAt = 1_000L),
            server = progress(currentTimeMs = 40_000L, lastUpdateAt = 4_000L),
            isActivePlaybackForItem = false,
        )

        assertTrue(resolution is ProgressConflictResolution.ApplyServer)
    }

    @Test
    fun `defers visible newer server jump during active playback`() {
        val resolution = ProgressConflictPolicy.resolve(
            local = progress(currentTimeMs = 10_000L, lastUpdateAt = 1_000L),
            server = progress(currentTimeMs = 40_000L, lastUpdateAt = 4_000L),
            isActivePlaybackForItem = true,
        )

        assertTrue(resolution is ProgressConflictResolution.DeferServer)
    }

    @Test
    fun `keeps local progress when local timestamp is newer`() {
        val resolution = ProgressConflictPolicy.resolve(
            local = progress(currentTimeMs = 40_000L, lastUpdateAt = 4_000L),
            server = progress(currentTimeMs = 10_000L, lastUpdateAt = 1_000L),
            isActivePlaybackForItem = false,
        )

        assertTrue(resolution is ProgressConflictResolution.KeepLocal)
    }

    @Test
    fun `treats clock drift inside tolerance as equivalent`() {
        val resolution = ProgressConflictPolicy.resolve(
            local = progress(currentTimeMs = 10_000L, lastUpdateAt = 1_000L),
            server = progress(currentTimeMs = 12_000L, lastUpdateAt = 2_500L),
            isActivePlaybackForItem = false,
        )

        assertTrue(resolution is ProgressConflictResolution.IgnoreEquivalent)
    }

    @Test
    fun `replays local only when it is newer than server beyond tolerance`() {
        val local = progress(currentTimeMs = 40_000L, lastUpdateAt = 5_500L)

        assertTrue(
            ProgressConflictPolicy.shouldReplayLocal(
                local,
                progress(currentTimeMs = 10_000L, lastUpdateAt = 3_000L),
            ),
        )
        assertFalse(
            ProgressConflictPolicy.shouldReplayLocal(
                local,
                progress(currentTimeMs = 10_000L, lastUpdateAt = 4_000L),
            ),
        )
    }

    private fun progress(
        currentTimeMs: Long,
        lastUpdateAt: Long,
    ) = MediaProgressEntity(
        bookId = "book",
        currentTimeMs = currentTimeMs,
        durationMs = 100_000L,
        progressFraction = currentTimeMs / 100_000.0,
        isFinished = false,
        hideFromContinueListening = false,
        lastUpdateAt = lastUpdateAt,
        startedAt = 500L,
        finishedAt = null,
        pendingUpload = false,
    )
}
