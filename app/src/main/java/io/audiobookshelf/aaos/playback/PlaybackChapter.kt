package io.audiobookshelf.aaos.playback

data class PlaybackChapter(
    val title: String?,
    val startMs: Long,
    val endMs: Long,
)

internal fun List<PlaybackChapter>.validateChapters(): List<PlaybackChapter> {
    var previousEndMs = 0L
    forEach { chapter ->
        require(chapter.startMs >= previousEndMs && chapter.endMs > chapter.startMs && chapter.endMs <= Long.MAX_VALUE / 1_000L) {
            "Chapter boundaries must be non-negative, ordered and non-overlapping."
        }
        previousEndMs = chapter.endMs
    }
    return this
}

/** The time range exposed to the media host; synchronization always uses book positions. */
internal data class PlaybackProgressRange(
    val startMs: Long,
    val durationMs: Long?,
    val chapter: PlaybackChapter? = null,
) {
    fun relativePosition(bookPositionMs: Long): Long =
        (bookPositionMs - startMs).coerceIn(0L, durationMs ?: Long.MAX_VALUE)

    fun bookPosition(relativePositionMs: Long): Long =
        startMs + relativePositionMs.coerceIn(0L, durationMs ?: (Long.MAX_VALUE - startMs))
}

internal fun playbackProgressRange(
    chapters: List<PlaybackChapter>,
    bookDurationMs: Long?,
    bookPositionMs: Long,
    showChapter: Boolean,
): PlaybackProgressRange {
    val duration = bookDurationMs?.takeIf { it > 0L }
    val chapter = if (showChapter) {
        chapters.firstOrNull {
            bookPositionMs >= it.startMs &&
                (bookPositionMs < it.endMs || (bookPositionMs == duration && it.endMs == duration)) &&
                (duration == null || it.endMs <= duration)
        }
    } else {
        null
    }
    return if (chapter != null) {
        PlaybackProgressRange(chapter.startMs, chapter.endMs - chapter.startMs, chapter)
    } else {
        PlaybackProgressRange(0L, duration)
    }
}
