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
        val local = progress(currentTimeMs = 20_000L, lastUpdateAt = 5_500L)

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

    @Test
    fun `server position clearly ahead wins replay despite older timestamp`() {
        val local = progress(currentTimeMs = 20_000L, lastUpdateAt = 8_000L)
        val server = progress(currentTimeMs = 80_000L, lastUpdateAt = 4_000L)

        assertFalse(ProgressConflictPolicy.shouldReplayLocal(local, server))
    }

    @Test
    fun `active playback seeks forward once for a new server update`() {
        val server = progress(currentTimeMs = 80_000L, lastUpdateAt = 5_000L)

        val first = ProgressConflictPolicy.reconcileActivePlayback(
            currentPositionMs = 20_000L,
            lastAppliedServerUpdateAt = 0L,
            server = server,
        )
        val repeated = ProgressConflictPolicy.reconcileActivePlayback(
            currentPositionMs = 20_000L,
            lastAppliedServerUpdateAt = 5_000L,
            server = server,
        )

        assertTrue(first is ActiveProgressReconciliation.SeekForwardOnce)
        assertTrue(repeated is ActiveProgressReconciliation.KeepCurrent)
    }

    @Test
    fun `active playback never seeks backward or for a small difference`() {
        val behind = progress(currentTimeMs = 10_000L, lastUpdateAt = 5_000L)
        val slightlyAhead = progress(currentTimeMs = 45_000L, lastUpdateAt = 6_000L)

        assertTrue(
            ProgressConflictPolicy.reconcileActivePlayback(50_000L, 0L, behind) is
                ActiveProgressReconciliation.KeepCurrent,
        )
        assertTrue(
            ProgressConflictPolicy.reconcileActivePlayback(20_000L, 0L, slightlyAhead) is
                ActiveProgressReconciliation.KeepCurrent,
        )
    }

    @Test
    fun `finished server progress seeks to the known duration`() {
        val server = progress(
            currentTimeMs = 0L,
            lastUpdateAt = 5_000L,
            isFinished = true,
        )

        val reconciliation = ProgressConflictPolicy.reconcileActivePlayback(
            currentPositionMs = 20_000L,
            lastAppliedServerUpdateAt = 0L,
            server = server,
        )

        assertTrue(
            reconciliation is ActiveProgressReconciliation.SeekForwardOnce &&
                reconciliation.positionMs == 100_000L,
        )
    }

    private fun progress(
        currentTimeMs: Long,
        lastUpdateAt: Long,
        isFinished: Boolean = false,
    ) = MediaProgressEntity(
        bookId = "book",
        currentTimeMs = currentTimeMs,
        durationMs = 100_000L,
        progressFraction = currentTimeMs / 100_000.0,
        isFinished = isFinished,
        hideFromContinueListening = false,
        lastUpdateAt = lastUpdateAt,
        startedAt = 500L,
        finishedAt = null,
        pendingUpload = false,
    )
}
