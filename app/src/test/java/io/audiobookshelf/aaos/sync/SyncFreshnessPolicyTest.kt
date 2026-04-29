package io.audiobookshelf.aaos.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFreshnessPolicyTest {
    @Test
    fun `refreshes when no successful sync timestamp exists`() {
        val snapshot = SyncSnapshot(status = SyncStatus.IDLE)

        assertTrue(SyncFreshnessPolicy.shouldRefresh(snapshot, nowMs = 10_000L))
    }

    @Test
    fun `keeps fresh populated catalog`() {
        val snapshot = SyncSnapshot(
            status = SyncStatus.SUCCESS,
            libraryCount = 1,
            bookCount = 10,
            authorCount = 5,
            lastSyncedAt = 1_000L,
        )

        assertFalse(
            SyncFreshnessPolicy.shouldRefresh(
                snapshot = snapshot,
                nowMs = 1_000L + SyncFreshnessPolicy.DEFAULT_MAX_CACHE_AGE_MS - 1L,
            ),
        )
    }

    @Test
    fun `refreshes stale catalog`() {
        val snapshot = SyncSnapshot(
            status = SyncStatus.SUCCESS,
            libraryCount = 1,
            bookCount = 10,
            authorCount = 5,
            lastSyncedAt = 1_000L,
        )

        assertTrue(
            SyncFreshnessPolicy.shouldRefresh(
                snapshot = snapshot,
                nowMs = 1_000L + SyncFreshnessPolicy.DEFAULT_MAX_CACHE_AGE_MS,
            ),
        )
    }

    @Test
    fun `refreshes empty catalog even when timestamp is fresh`() {
        val snapshot = SyncSnapshot(
            status = SyncStatus.SUCCESS,
            libraryCount = 0,
            bookCount = 0,
            authorCount = 0,
            lastSyncedAt = 1_000L,
        )

        assertTrue(SyncFreshnessPolicy.shouldRefresh(snapshot, nowMs = 2_000L))
    }
}
