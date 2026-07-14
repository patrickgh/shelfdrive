package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSkipPolicyTest {
    @Test
    fun `accepts all configured skip increments`() {
        PlaybackSkipPolicy.supportedSkipIncrementSeconds.forEach { seconds ->
            assertEquals(seconds, PlaybackSkipPolicy.secondsFromPreference(seconds.toString()))
        }
    }

    @Test
    fun `uses fifteen seconds for missing or invalid preference`() {
        assertEquals(
            PlaybackSkipPolicy.DEFAULT_SKIP_INCREMENT_SECONDS,
            PlaybackSkipPolicy.secondsFromPreference(null),
        )
        assertEquals(
            PlaybackSkipPolicy.DEFAULT_SKIP_INCREMENT_SECONDS,
            PlaybackSkipPolicy.secondsFromPreference("45"),
        )
        assertEquals(
            PlaybackSkipPolicy.DEFAULT_SKIP_INCREMENT_SECONDS,
            PlaybackSkipPolicy.secondsFromPreference("invalid"),
        )
    }
}
