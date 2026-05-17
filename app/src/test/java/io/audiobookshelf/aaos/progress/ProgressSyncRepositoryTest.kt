package io.audiobookshelf.aaos.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressSyncRepositoryTest {

    @Test
    fun `calculates progress fraction from logical position and duration`() {
        val currentTimeMs = 109 * 60 * 1000L
        val durationMs = 609 * 60 * 1000L

        assertEquals(0.17898, calculateProgressFraction(currentTimeMs, durationMs) ?: 0.0, 0.00001)
    }

    @Test
    fun `does not calculate progress without known duration`() {
        assertNull(calculateProgressFraction(currentTimeMs = 60_000L, durationMs = null))
    }

    @Test
    fun `clamps impossible progress values`() {
        assertEquals(0.0, calculateProgressFraction(currentTimeMs = -10_000L, durationMs = 100_000L) ?: 1.0, 0.0)
        assertEquals(1.0, calculateProgressFraction(currentTimeMs = 120_000L, durationMs = 100_000L) ?: 0.0, 0.0)
    }
}
