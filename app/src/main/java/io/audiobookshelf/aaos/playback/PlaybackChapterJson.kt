package io.audiobookshelf.aaos.playback

import org.json.JSONArray
import org.json.JSONObject

/** Audiobookshelf uses seconds; use the same truncation as the audio-track offsets. */
internal fun parsePlaybackChapters(array: JSONArray?): List<PlaybackChapter> {
    if (array == null) return emptyList()
    return List(array.length()) { index ->
        val chapter = array.getJSONObject(index)
        fun timeMs(key: String): Long {
            val seconds = (chapter.get(key) as? Number)?.toDouble()
            require(seconds != null && seconds.isFinite() && seconds >= 0 && seconds < Long.MAX_VALUE / 1_000_000.0) {
                "Invalid chapter $index $key."
            }
            return (seconds * 1_000).toLong()
        }
        PlaybackChapter(
            title = chapter.optString("title").takeIf { !chapter.isNull("title") && it.isNotBlank() },
            startMs = timeMs("start"),
            endMs = timeMs("end"),
        )
    }.validateChapters()
}

internal fun decodePlaybackChapters(raw: String): List<PlaybackChapter> {
    val array = JSONArray(raw)
    return List(array.length()) { index ->
        val chapter = array.getJSONObject(index)
        PlaybackChapter(
            title = chapter.optString("title").takeIf { !chapter.isNull("title") && it.isNotBlank() },
            startMs = chapter.getLong("startMs"),
            endMs = chapter.getLong("endMs"),
        )
    }.validateChapters()
}

internal fun encodePlaybackChapters(chapters: List<PlaybackChapter>): String =
    JSONArray().apply {
        chapters.forEach { chapter ->
            put(JSONObject().apply {
                put("title", chapter.title)
                put("startMs", chapter.startMs)
                put("endMs", chapter.endMs)
            })
        }
    }.toString()
