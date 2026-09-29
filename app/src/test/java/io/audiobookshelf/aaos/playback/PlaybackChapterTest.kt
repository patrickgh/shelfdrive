package io.audiobookshelf.aaos.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackChapterTest {
    private val chapters = listOf(
        PlaybackChapter("First", 0L, 60_000L),
        PlaybackChapter("Second", 60_000L, 180_000L),
    )

    @Test
    fun `book mode keeps absolute time even when chapters exist`() {
        val range = playbackProgressRange(chapters, 180_000L, 75_000L, false)
        assertEquals(180_000L, range.durationMs)
        assertEquals(75_000L, range.relativePosition(75_000L))
        assertEquals(75_000L, range.bookPosition(75_000L))
        assertNull(range.chapter)
    }

    @Test
    fun `chapter positions and seeks use book offsets independent of audio files`() {
        val range = playbackProgressRange(chapters, 180_000L, 75_000L, true)
        assertEquals(chapters[1], range.chapter)
        assertEquals(120_000L, range.durationMs)
        assertEquals(15_000L, range.relativePosition(75_000L))
        assertEquals(105_000L, range.bookPosition(45_000L))
        // This chapter spans the boundary between the two underlying files.
        val queue = listOf(
            PlaybackTrack("a", "A", "a", null, 90_000L, 0L),
            PlaybackTrack("b", "B", "b", null, 90_000L, 90_000L),
        )
        assertEquals(QueueStartPosition(1, 15_000L), PlaybackQueueMath.locateStartPosition(queue, range.bookPosition(45_000L)))
    }

    @Test
    fun `exact chapter boundary selects the next chapter`() {
        assertEquals(chapters[0], playbackProgressRange(chapters, 180_000L, 59_999L, true).chapter)
        val next = playbackProgressRange(chapters, 180_000L, 60_000L, true)
        assertEquals(chapters[1], next.chapter)
        assertEquals(0L, next.relativePosition(60_000L))
    }

    @Test
    fun `book end remains at the end of the final chapter`() {
        val range = playbackProgressRange(chapters, 180_000L, 180_000L, true)
        assertEquals(chapters[1], range.chapter)
        assertEquals(120_000L, range.relativePosition(180_000L))
    }

    @Test
    fun `position buffer and seeks are bounded to the displayed chapter`() {
        val range = playbackProgressRange(chapters, 180_000L, 75_000L, true)
        assertEquals(0L, range.relativePosition(0L))
        assertEquals(120_000L, range.relativePosition(200_000L))
        assertEquals(60_000L, range.bookPosition(-5_000L))
        assertEquals(180_000L, range.bookPosition(Long.MAX_VALUE))
    }

    @Test
    fun `missing chapters gaps and chapters beyond duration use book progress`() {
        listOf(
            emptyList(),
            listOf(PlaybackChapter("Later", 100_000L, 180_000L)),
            listOf(PlaybackChapter("Too long", 0L, 190_000L)),
        ).forEach { unavailable ->
            val range = playbackProgressRange(unavailable, 180_000L, 75_000L, true)
            assertNull(range.chapter)
            assertEquals(180_000L, range.durationMs)
            assertEquals(75_000L, range.relativePosition(75_000L))
        }
    }

    @Test
    fun `known chapters work without a known book duration`() {
        assertEquals(chapters[1], playbackProgressRange(chapters, null, 75_000L, true).chapter)
        assertNull(playbackProgressRange(emptyList(), null, 75_000L, true).durationMs)
    }

    @Test
    fun `rejects invalid and overlapping chapter boundaries`() {
        listOf(
            listOf(PlaybackChapter(null, -1L, 100L)),
            listOf(PlaybackChapter(null, 100L, 100L)),
            listOf(PlaybackChapter(null, 100L, 99L)),
            listOf(PlaybackChapter(null, 0L, 100L), PlaybackChapter(null, 99L, 200L)),
            chapters.reversed(),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.validateChapters() }
        }
        assertEquals(chapters, chapters.validateChapters())
        assertEquals(emptyList<PlaybackChapter>(), emptyList<PlaybackChapter>().validateChapters())
    }
}
