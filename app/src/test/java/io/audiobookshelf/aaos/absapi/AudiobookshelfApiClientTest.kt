package io.audiobookshelf.aaos.absapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudiobookshelfApiClientTest {

    @Test
    fun `media progress update uses epoch millis for ABS timestamps`() {
        val fields = mediaProgressUpdateRequestFields(
            MediaProgressUpdateRequest(
                currentTimeMs = 12_345L,
                durationMs = 100_000L,
                progressFraction = 0.12345,
                isFinished = false,
                hideFromContinueListening = false,
                startedAt = 1_688_120_083_771L,
                finishedAt = null,
            ),
        )

        assertEquals(12.345, fields["currentTime"])
        assertEquals(100.0, fields["duration"])
        assertEquals(0.12345, fields["progress"])
        assertFalse(fields["isFinished"] as Boolean)
        assertFalse(fields["hideFromContinueListening"] as Boolean)
        assertEquals(1_688_120_083_771L, fields["startedAt"])
        assertFalse(fields.containsKey("finishedAt"))
    }
}
