package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test
    fun `rewinds pause position by fifteen seconds`() {
        val result = PlaybackResumePolicy.positionAfterPause(60_000L)

        assertEquals(45_000L, result)
    }

    @Test
    fun `clamps pause position at zero`() {
        val result = PlaybackResumePolicy.positionAfterPause(10_000L)

        assertEquals(0L, result)
    }

    @Test
    fun `clamps negative current position at zero`() {
        val result = PlaybackResumePolicy.positionAfterPause(-5_000L)

        assertEquals(0L, result)
    }
}
