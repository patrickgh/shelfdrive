package io.audiobookshelf.aaos.progress

import io.audiobookshelf.aaos.catalog.persistence.MediaProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressConflictPolicyTest {

    @Test
    fun `uploads local progress when server is behind`() {
        val decision = ProgressConflictPolicy.decide(
            currentPositionMs = 50_000L,
            server = progress(currentTimeMs = 20_000L),
        )

        assertTrue(decision is ProgressUpdateDecision.UploadLocal)
    }

    @Test
    fun `uploads local progress below forward threshold`() {
        val decision = ProgressConflictPolicy.decide(
            currentPositionMs = 20_000L,
            server = progress(currentTimeMs = 49_999L),
        )

        assertTrue(decision is ProgressUpdateDecision.UploadLocal)
    }

    @Test
    fun `seeks forward at exact threshold`() {
        val decision = ProgressConflictPolicy.decide(
            currentPositionMs = 20_000L,
            server = progress(currentTimeMs = 50_000L),
        )

        assertTrue(decision is ProgressUpdateDecision.SeekForward)
        assertEquals(50_000L, (decision as ProgressUpdateDecision.SeekForward).positionMs)
    }

    @Test
    fun `finished server progress seeks to duration`() {
        val decision = ProgressConflictPolicy.decide(
            currentPositionMs = 20_000L,
            server = progress(currentTimeMs = 0L, isFinished = true),
        )

        assertTrue(decision is ProgressUpdateDecision.SeekForward)
        assertEquals(100_000L, (decision as ProgressUpdateDecision.SeekForward).positionMs)
    }

    private fun progress(
        currentTimeMs: Long,
        isFinished: Boolean = false,
    ) = MediaProgressEntity(
        bookId = "book",
        currentTimeMs = currentTimeMs,
        durationMs = 100_000L,
        progressFraction = currentTimeMs / 100_000.0,
        isFinished = isFinished,
        hideFromContinueListening = false,
        lastUpdateAt = 5_000L,
        startedAt = 500L,
        finishedAt = null,
        pendingUpload = false,
    )
}
