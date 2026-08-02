package io.audiobookshelf.aaos.progress

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressModelsTest {
    @Test
    fun `track transitions do not invalidate the browse tree`() {
        assertFalse(PlaybackProgressReason.TRACK_CHANGED.shouldRefreshBrowse)
    }

    @Test
    fun `completed user-visible progress changes refresh recent items`() {
        assertTrue(PlaybackProgressReason.PAUSED.shouldRefreshBrowse)
        assertTrue(PlaybackProgressReason.STOPPED.shouldRefreshBrowse)
        assertTrue(PlaybackProgressReason.ENDED.shouldRefreshBrowse)
    }
}
